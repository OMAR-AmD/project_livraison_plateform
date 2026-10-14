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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private RedisTemplate<String, String> redis;
    private NotificationService notifications;

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

        redis = mock(RedisTemplate.class);
        notifications = mock(NotificationService.class);

        service = new DeliveryService(
                repository,
                mock(UserRepository.class),
                notifications,
                redis,
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
        assertEquals(0.0, delivery.getProofDistanceM(),
                "sealed at the drop-off, the measured distance is zero");
        assertEquals(DeliveryStatus.DELIVERED, delivery.getStatus());

        verify(notifications, times(1)).createNotification(eq(client), anyString());

        ArgumentCaptor<SealChainHead> heads = ArgumentCaptor.forClass(SealChainHead.class);
        verify(headRepository).save(heads.capture());
        assertEquals(delivery.getProofHash(), heads.getValue().getHeadHash(),
                "the head must advance to the new seal in the same transaction");
        assertEquals(1L, heads.getValue().getLastSeq());

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
        assertEquals(first.getPrevHash(), tomb.getPrevHash());
        assertEquals(1L, tomb.getSealSeq());
        assertEquals(courier.getId(), tomb.getCourierId(), "the tomb keeps whose seal it was");
        assertEquals(client.getId(), tomb.getClientId());
        assertEquals(first.getDeliveredLat(), tomb.getDeliveredLat());
        assertEquals(first.getDeliveredLng(), tomb.getDeliveredLng());
        assertEquals(first.getDeliveredAt(), tomb.getDeliveredAt());
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

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.unassignCourier(delivery.getId()));
        assertTrue(e.getMessage().contains("names its courier"),
                "the refusal must name the reason, so a wrong guard throwing the same type cannot hide: "
                        + e.getMessage());
    }

    @Test
    @DisplayName("unassigning a courier-less delivery is refused before anything else")
    void unassigningACourierlessDeliveryIsRefused() {
        Delivery delivery = inTransitOrder();
        delivery.setCourier(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.unassignCourier(delivery.getId()));
        assertTrue(e.getMessage().contains("no courier to remove"),
                "each guard must be distinguishable by its message: " + e.getMessage());
    }

    @Test
    @DisplayName("an unsealed delivery can still be unassigned")
    void anUnsealedDeliveryCanBeUnassigned() {
        Delivery delivery = inTransitOrder();

        service.unassignCourier(delivery.getId());

        assertNull(delivery.getCourier());
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
    }

    @Test
    @DisplayName("a sealed delivery cannot be sealed twice")
    void aSealedDeliveryCannotBeResealed() {
        Delivery delivery = sealFirst(inTransitOrder());
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED,
                        LAT, LNG, null, courier));
        assertTrue(e.getMessage().contains("already sealed"),
                "the once-ever rule must be distinguishable: " + e.getMessage());
    }

    @Test
    @DisplayName("without coordinates the seal falls back to the broadcast position")
    void sealingFallsBackToTheBroadcastPosition() {
        // The courier UI confirms with coordinates; the path that actually runs
        // in the demo sends status alone and seals from the last broadcast.
        // Only latitude is missing: one missing coordinate is enough to take
        // the fallback, and the broadcast replaces both — a point mixed from
        // two fixes would be neither.
        Delivery delivery = inTransitOrder();
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(
                org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("livreur:position:" + delivery.getId()))
                .thenReturn("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + "}");
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, null, LNG, null, courier);

        assertEquals(LAT, delivery.getDeliveredLat());
        assertEquals(LNG, delivery.getDeliveredLng());
    }

    @Test
    @DisplayName("a missing longitude alone is also enough to take the broadcast fallback")
    void sealingFallsBackWhenOnlyLongitudeIsMissing() {
        Delivery delivery = inTransitOrder();
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(
                org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("livreur:position:" + delivery.getId()))
                .thenReturn("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + "}");
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, LAT, null, null, courier);

        assertEquals(LAT, delivery.getDeliveredLat());
        assertEquals(LNG, delivery.getDeliveredLng());
    }

    @Test
    @DisplayName("without a destination the seal carries no distance claim")
    void sealingWithoutADestinationCarriesNoDistance() {
        Delivery delivery = inTransitOrder();
        delivery.setDropoffLat(null);
        delivery.setDropoffLng(null);
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);

        assertNull(delivery.getProofDistanceM(),
                "no destination means no distance to measure, not zero");
        assertTrue(service.getProof(delivery.getId()).verified());
    }

    @Test
    @DisplayName("a valid code with no position seals at the drop-off and records the factor")
    void aValidCodeWithoutPositionSealsAtTheDropoff() {
        Delivery delivery = inTransitOrder();
        String code = proofService.handoverCode(delivery.getId());
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, null, null, code, courier);

        assertEquals(LAT, delivery.getDeliveredLat());
        assertEquals(LNG, delivery.getDeliveredLng());
        assertNull(delivery.getProofDistanceM());
        assertTrue(delivery.isCodeVerified());
        assertNotNull(delivery.getCodeVerifiedAt(), "the proof keeps when the code factor was verified");
        assertTrue(service.getProof(delivery.getId()).verified());
    }

    @Test
    @DisplayName("exactly 500 m still seals: the refusal starts past the limit")
    void exactlyFiveHundredMetresStillSeals() {
        // The boundary >= versus > is indistinguishable through coordinates —
        // both refuse at 501 — so the distance is stubbed at exactly 500.0.
        DeliveryProofService proof = mock(DeliveryProofService.class);
        when(proof.haversineMeters(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(500.0);
        java.time.LocalDateTime at = java.time.LocalDateTime.of(2026, 3, 1, 10, 0, 0);
        when(proof.sealChained(any(), any(), any(), anyDouble(), anyDouble(),
                any(java.time.LocalDateTime.class), any()))
                .thenReturn(new DeliveryProofService.Seal("b".repeat(64), at));
        DeliveryService bounded = new DeliveryService(
                repository, mock(UserRepository.class), mock(NotificationService.class), redis,
                mock(SimpMessagingTemplate.class), new ObjectMapper(),
                mock(RouteOptimizationService.class), mock(PricingService.class),
                mock(TrajectoryFraudService.class), mock(FraudTrailStore.class), proof,
                mock(HandoverCodeRateLimiter.class), headRepository, tombRepository);

        Delivery delivery = inTransitOrder();
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        bounded.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);

        assertEquals("b".repeat(64), delivery.getProofHash());
    }

    @Test
    @DisplayName("a tombstone keeps a null courier as null: the link still holds")
    void aTombstoneKeepsANullCourier() {
        Delivery delivery = sealFirst(inTransitOrder());
        delivery.setCourier(null); // unassigned before the terminal rule existed

        service.deleteDelivery(delivery.getId());

        ArgumentCaptor<SealTombstone> tombs = ArgumentCaptor.forClass(SealTombstone.class);
        verify(tombRepository).save(tombs.capture());
        assertNull(tombs.getValue().getCourierId());

        when(repository.findByProofHashNotNullOrderBySealSeqAscIdAsc()).thenReturn(List.of());
        when(tombRepository.findAllByOrderBySealSeqAsc()).thenReturn(List.of(tombs.getValue()));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertTrue(result.ok());
        assertEquals(1, result.tombstones());
    }

    @Test
    @DisplayName("a row with no sequence sorts last, deterministically")
    void aRowWithNoSequenceSortsLast() {
        Delivery genesis = sealFirst(inTransitOrder());
        Delivery second = sealAfter(inTransitOrder(), genesis);

        // A hand-inserted row: a genuine seal, but no sequence number.
        Delivery stray = inTransitOrder();
        DeliveryProofService.Seal seal = proofService.sealChained(stray.getId(),
                courier.getId(), client.getId(), LAT, LNG,
                java.time.LocalDateTime.of(2026, 3, 2, 10, 0, 0), second.getProofHash());
        stray.setDeliveredLat(LAT);
        stray.setDeliveredLng(LNG);
        stray.setDeliveredAt(seal.sealedAt());
        stray.setProofHash(seal.hash());
        stray.setPrevHash(second.getProofHash());
        stray.setSealSeq(null);
        when(repository.findById(stray.getId())).thenReturn(Optional.of(stray));

        when(repository.findByProofHashNotNullOrderBySealSeqAscIdAsc())
                .thenReturn(List.of(genesis, second, stray));

        assertTrue(service.verifySealChain().ok());
    }

    @Test
    @DisplayName("a log whose every seal was deleted still verifies through tombstones")
    void aFullyDeletedLogVerifiesThroughTombstones() {
        Delivery delivery = sealFirst(inTransitOrder());
        service.deleteDelivery(delivery.getId());

        ArgumentCaptor<SealTombstone> tombs = ArgumentCaptor.forClass(SealTombstone.class);
        verify(tombRepository).save(tombs.capture());

        when(repository.findByProofHashNotNullOrderBySealSeqAscIdAsc()).thenReturn(List.of());
        when(tombRepository.findAllByOrderBySealSeqAsc()).thenReturn(List.of(tombs.getValue()));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertTrue(result.ok(), "recorded deletions are evidence, not gaps");
        assertEquals(1, result.sealed());
        assertEquals(1, result.tombstones());
    }

    @Test
    @DisplayName("a valid code waives the proximity rule but still measures the distance")
    void aValidCodeWaivesProximityButStillMeasures() {
        // Six hundred metres out: without the code this is refused, with it it
        // seals — and the proof still records how far away the courier was.
        Delivery delivery = inTransitOrder();
        String code = proofService.handoverCode(delivery.getId());
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED,
                LAT + 600.0 / 111320, LNG, code, courier);

        assertTrue(service.getProof(delivery.getId()).verified());
        assertNotNull(delivery.getProofDistanceM());
        assertTrue(delivery.getProofDistanceM() > 500.0,
                "the waiver must not zero the measurement: " + delivery.getProofDistanceM());
    }

    @Test
    @DisplayName("confirming a delivery answers the sealed order, not nothing")
    void confirmingAnswersTheSealedOrder() {
        Delivery delivery = inTransitOrder();
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        com.delivery.backend.modules.delivery.dto.DeliveryResponse response =
                service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED,
                        LAT, LNG, null, courier);

        assertNotNull(response);
        assertEquals(delivery.getId(), response.getId());
    }

    @Test
    @DisplayName("an in-transit order in a client list carries the live position")
    void anInTransitOrderCarriesTheLivePosition() {
        Delivery delivery = inTransitOrder();
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(
                org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("livreur:position:" + delivery.getId()))
                .thenReturn("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + "}");
        when(repository.findByClientOrderByCreatedAtDesc(client)).thenReturn(List.of(delivery));

        com.delivery.backend.modules.delivery.dto.DeliveryResponse response =
                service.getDeliveriesForClient(client).get(0);

        assertEquals(LAT, response.getCourierLatitude());
        assertEquals(LNG, response.getCourierLongitude());
    }

    @Test
    @DisplayName("a successful code is reported to the limiter, not silently consumed")
    void aSuccessfulCodeIsReportedToTheLimiter() {
        HandoverCodeRateLimiter limiter = mock(HandoverCodeRateLimiter.class);
        DeliveryService coded = new DeliveryService(
                repository, mock(UserRepository.class), notifications, redis,
                mock(SimpMessagingTemplate.class), new ObjectMapper(),
                mock(RouteOptimizationService.class), mock(PricingService.class),
                mock(TrajectoryFraudService.class), mock(FraudTrailStore.class), proofService,
                limiter, headRepository, tombRepository);

        Delivery delivery = inTransitOrder();
        String code = proofService.handoverCode(delivery.getId());
        when(headRepository.lockHead()).thenReturn(Optional.of(head(null, 0)));

        coded.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, LAT, LNG, code, courier);

        verify(limiter).checkAllowed(delivery.getId(), courier.getId());
        verify(limiter).recordSuccess(delivery.getId());
    }

    @Test
    @DisplayName("unassigning notifies both parties and answers the new state")
    void unassigningNotifiesBothParties() {
        Delivery delivery = inTransitOrder();

        com.delivery.backend.modules.delivery.dto.DeliveryResponse response =
                service.unassignCourier(delivery.getId());

        assertNotNull(response);
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        verify(notifications, times(2)).createNotification(any(), anyString());
    }

    @Test
    @DisplayName("status moves only forward along the courier path")
    void statusMovesOnlyForward() {
        Delivery delivery = inTransitOrder();
        delivery.setStatus(DeliveryStatus.ASSIGNED);

        IllegalArgumentException arrived = assertThrows(IllegalArgumentException.class,
                () -> service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.ARRIVED,
                        null, null, null, courier));
        assertTrue(arrived.getMessage().contains("only while it is in transit"),
                "arrival from assigned must name its precondition: " + arrived.getMessage());

        IllegalArgumentException delivered = assertThrows(IllegalArgumentException.class,
                () -> service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED,
                        LAT, LNG, null, courier));
        assertTrue(delivered.getMessage().contains("once it is in transit or arrived"),
                "delivery from assigned must name its precondition: " + delivered.getMessage());
    }
}
