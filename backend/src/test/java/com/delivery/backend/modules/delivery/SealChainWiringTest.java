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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the chain is actually on the sealing path, not merely correct in
 * isolation.
 *
 * <p>{@link SealChainTest} pins the chain maths; this pins the wiring, with a
 * real {@link DeliveryProofService} and a mocked repository. The failures it
 * guards against are quiet: seals that never store a link, a {@code getProof}
 * that still verifies v1-only (which would report every chained seal as
 * tampered), or a {@code verifySealChain} that reads the rows out of order.
 */
class SealChainWiringTest {

    private static final double LAT = 33.5731;
    private static final double LNG = -7.5898;

    private DeliveryRepository repository;
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
                        Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC)));
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

    private Delivery seal(Delivery delivery) {
        when(repository.findFirstByProofHashNotNullOrderByDeliveredAtDescIdDesc())
                .thenReturn(Optional.empty());
        service.updateDeliveryStatus(delivery.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);
        return delivery;
    }

    @Test
    @DisplayName("the first seal starts the chain and still verifies on read-back")
    void theFirstSealStartsTheChain() {
        Delivery delivery = seal(inTransitOrder());

        assertEquals(DeliveryProofService.CHAIN_GENESIS, delivery.getPrevHash());
        assertEquals(64, delivery.getProofHash().length());

        when(repository.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        DeliveryService.DeliveryProofView proof = service.getProof(delivery.getId());
        assertTrue(proof.verified(), "a chained seal must verify on read-back, not just at sealing");
        assertTrue(proof.verifiable());
        assertEquals(DeliveryProofService.CHAIN_GENESIS, proof.prevHash());
    }

    @Test
    @DisplayName("each new seal links against the current head")
    void eachNewSealLinksAgainstTheHead() {
        Delivery first = seal(inTransitOrder());

        Delivery second = inTransitOrder();
        when(repository.findFirstByProofHashNotNullOrderByDeliveredAtDescIdDesc())
                .thenReturn(Optional.of(first));
        service.updateDeliveryStatus(second.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);

        assertEquals(first.getProofHash(), second.getPrevHash(),
                "the second seal must commit to the first seal's hash");
    }

    @Test
    @DisplayName("verifySealChain walks the repository rows in order and reports intact")
    void verifySealChainWalksInOrder() {
        Delivery first = seal(inTransitOrder());

        Delivery second = inTransitOrder();
        when(repository.findFirstByProofHashNotNullOrderByDeliveredAtDescIdDesc())
                .thenReturn(Optional.of(first));
        service.updateDeliveryStatus(second.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);

        when(repository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc())
                .thenReturn(List.of(first, second));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertTrue(result.ok());
        assertEquals(2, result.sealed());
        assertNull(result.brokenAt());
    }

    @Test
    @DisplayName("verifySealChain points at the row whose fields were altered")
    void verifySealChainPointsAtTampering() {
        Delivery first = seal(inTransitOrder());

        Delivery second = inTransitOrder();
        when(repository.findFirstByProofHashNotNullOrderByDeliveredAtDescIdDesc())
                .thenReturn(Optional.of(first));
        service.updateDeliveryStatus(second.getId(), DeliveryStatus.DELIVERED, LAT, LNG, null, courier);

        second.setDeliveredLat(LAT + 0.5); // altered after sealing
        when(repository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc())
                .thenReturn(List.of(first, second));

        DeliveryProofService.ChainVerification result = service.verifySealChain();
        assertEquals(false, result.ok());
        assertEquals(second.getId(), result.brokenAt());
    }
}
