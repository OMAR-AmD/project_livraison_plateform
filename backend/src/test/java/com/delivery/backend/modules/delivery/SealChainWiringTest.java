package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.ai.FraudTrailStore;
import com.delivery.backend.modules.ai.TrajectoryFraudService;
import com.delivery.backend.modules.auth.Role;
import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.auth.UserRepository;
import com.delivery.backend.modules.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the chain is actually on the sealing path, not merely correct in
 * isolation.
 *
 * <p>{@link SealChainTest} pins the chain maths; this pins the wiring, with a
 * real {@link DeliveryProofService} and mocked repositories. The failures it
 * guards against are quiet: seals that never store a link, a {@code getProof}
 * that still verifies v1-only (which would report every chained seal as
 * tampered), a seal path that reads the head without locking it, or a
 * {@code verifySealChain} that reads the rows out of order.
 */
class SealChainWiringTest {

    private static final double LAT = 33.5731;
    private static final double LNG = -7.5898;

    private DeliveryRepository repository;
    private SealChainHeadRepository headRepository;
    private SealTombstoneRepository tombRepository;
    private DeliveryProofService proofService;
    private DeliveryService service;

    private final User courier = new User("courier@swift.com", "x", Role.LIVREUR);
    private final User client = new User("client@swift.com", "x", Role.CLIENT);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        courier.setId(UUID.randomUUID());
        client.setId(UUID.randomUUID());

        repository = mock(DeliveryRepository.class);
        when(repository.save(any(Delivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        headRepository = mock(SealChainHeadRepository.class);
        tombRepository = mock(SealTombstoneRepository.class);
        when(tombRepository.findAllByOrderBySealSeqAsc()).thenReturn(List.of());

        proofService = new DeliveryProofService("test-proof-root-secret-for-chain-wiring-0123456789");

        service = new DeliveryService(
                repository,
                mock(UserRepository.class),
                mock(NotificationService.class),
                mock(RedisTemplate.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper(),
                mock(RouteOptimizationService.class),
                mock(PricingService.class),
                mock(TrajectoryFraudService.class),
                mock(FraudTrailStore.class),
                proofService,
                new HandoverCodeRateLimiter(5, 20, Duration.ofMinutes(15),
                        Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC)),
                headRepository,
                tombRepository);
    }

    private Delivery inTransitOrder() {
        Delivery delivery = new Delivery("parcel", "pickup", "dropoff", client);
        delivery.setId(UUID.randomUUID());
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        delivery.setCourier(courier);
        delivery.setDropoffLat(LAT);
        delivery.setDropoffLng(LNG);
        when(repository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        return delivery;
    }

    private static SealChainHead head(String hash, long seq) {
        SealChainHead head = new SealChainHead();
        head.setId(1L);
        head.setHeadHash(hash);
        head.setLastSeq(seq);
        return head;
    }

    /** Seals against an empty chain, like the first delivery ever. */
    private Delivery sealFirst(Delivery delivery) {
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));
        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);
        return delivery;
    }

    /** Seals against the given row as the current head. */
    private Delivery sealAfter(Delivery delivery, Delivery headRow) {
        when(headRepository.lockHead())
                .thenReturn(Optional.of(head(headRow.getProofHash(), headRow.getSealSeq())));
        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);
        return delivery;
    }

    @Test
    @DisplayName("the first seal starts the chain and still verifies on read-back")
    void theFirstSealStartsTheChain() {
        Delivery delivery = sealFirst(inTransitOrder());

        assertEquals(DeliveryProofService.CHAIN_GENESIS, delivery.getPrevHash());
        assertEquals(1L, delivery.getSealSeq());
        assertEquals(64, delivery.getProofHash().length());

        DeliveryService.DeliveryProofView proof = service.getProof(delivery.getId());
        assertTrue(proof.verified(), "a chained seal must verify on read-back, not just at sealing");
        assertTrue(proof.verifiable());
        assertEquals(DeliveryProofService.CHAIN_GENESIS, proof.prevHash());
    }

    @Test
    @DisplayName("each new seal links against the current head with the next sequence")
    void eachNewSealLinksAgainstTheHead() {
        Delivery first = sealFirst(inTransitOrder());
        Delivery second = sealAfter(inTransitOrder(), first);

        assertEquals(first.getProofHash(), second.getPrevHash(),
                "the second seal must commit to the first seal's hash");
        assertEquals(2L, second.getSealSeq());
    }

    @Test
    @DisplayName("sealing goes through the locked head, so concurrent seals line up")
    void sealingLocksTheHead() {
        sealFirst(inTransitOrder());

        verify(headRepository, times(1)).lockHead();
    }

    @Test
    @DisplayName("two seals against the same head read are a fork, and the walk points at it")
    void aForkIsDetectedAtTheSecondRow() {
        // The interleaving the head lock prevents: two transactions both read
        // head H before either commits, so both seals claim H. Each seal is
        // genuine on its own — only the walk can see the fork. Every lockHead
        // call answers with a fresh copy of the same committed state, the way
        // two real transactions would each see it.
        Delivery genesis = sealFirst(inTransitOrder());
        when(headRepository.lockHead()).thenAnswer(invocation ->
                Optional.of(head(genesis.getProofHash(), genesis.getSealSeq())));

        Delivery first = inTransitOrder();
        service.updateDeliveryStatus(first.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);
        Delivery second = inTransitOrder();
        service.updateDeliveryStatus(second.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);

        when(repository.findByProofHashNotNullOrderBySealSeqAscIdAsc())
                .thenReturn(List.of(genesis, first, second));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertEquals(false, result.ok());
        assertEquals(second.getId(), result.brokenAt());
    }

    @Test
    @DisplayName("verifySealChain walks the repository rows in order and reports intact")
    void verifySealChainWalksInOrder() {
        Delivery first = sealFirst(inTransitOrder());
        Delivery second = sealAfter(inTransitOrder(), first);

        when(repository.findByProofHashNotNullOrderBySealSeqAscIdAsc())
                .thenReturn(List.of(first, second));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertTrue(result.ok());
        assertEquals(2, result.sealed());
        assertEquals(0, result.tombstones());
        assertNull(result.brokenAt());
    }

    @Test
    @DisplayName("verifySealChain points at the row whose fields were altered")
    void verifySealChainPointsAtTampering() {
        Delivery first = sealFirst(inTransitOrder());
        Delivery second = sealAfter(inTransitOrder(), first);

        second.setDeliveredLat(LAT + 0.5); // altered after sealing
        when(repository.findByProofHashNotNullOrderBySealSeqAscIdAsc())
                .thenReturn(List.of(first, second));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertEquals(false, result.ok());
        assertEquals(second.getId(), result.brokenAt());
    }

    @Test
    @DisplayName("deleting a sealed delivery leaves a signed tombstone and the chain stays green")
    void deletingASealedDeliveryLeavesATombstone() {
        Delivery first = sealFirst(inTransitOrder());
        Delivery second = sealAfter(inTransitOrder(), first);

        service.deleteDelivery(first.getId());

        ArgumentCaptor<SealTombstone> tombs = ArgumentCaptor.forClass(SealTombstone.class);
        verify(tombRepository, times(1)).save(tombs.capture());
        SealTombstone tomb = tombs.getValue();
        assertEquals(first.getId(), tomb.getDeliveryId());
        assertEquals(first.getProofHash(), tomb.getProofHash());
        assertEquals(1L, tomb.getSealSeq());
        assertEquals("admin", tomb.getDeletedBy());
        verify(repository, times(1)).delete(first);

        // The walk merges the tombstone at its old position: still intact,
        // now with one recorded deletion.
        when(repository.findByProofHashNotNullOrderBySealSeqAscIdAsc())
                .thenReturn(List.of(second));
        when(tombRepository.findAllByOrderBySealSeqAsc()).thenReturn(List.of(tomb));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertTrue(result.ok());
        assertEquals(2, result.sealed());
        assertEquals(1, result.tombstones());
    }

    @Test
    @DisplayName("deleting an unsealed delivery leaves no tombstone")
    void deletingAnUnsealedDeliveryLeavesNoTombstone() {
        Delivery delivery = inTransitOrder();

        service.deleteDelivery(delivery.getId());

        verify(tombRepository, never()).save(any(SealTombstone.class));
        verify(repository, times(1)).delete(delivery);
    }

    @Test
    @DisplayName("a sealed delivery cannot be unassigned: the proof names its courier")
    void aSealedDeliveryCannotBeUnassigned() {
        Delivery delivery = sealFirst(inTransitOrder());

        assertThrows(IllegalArgumentException.class, () -> service.unassignCourier(delivery.getId()));
    }

    @Test
    @DisplayName("a sealed delivery cannot be sealed twice")
    void aSealedDeliveryCannotBeResealed() {
        Delivery delivery = sealFirst(inTransitOrder());
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED,
                        LAT, LNG, null, courier));
    }
}
