package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.ai.FraudTrailStore;
import com.delivery.backend.modules.ai.TrajectoryFraudService;
import com.delivery.backend.modules.auth.RateLimitExceededException;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the wrong-code limiter is actually on the delivery-confirmation path,
 * not merely correct in isolation.
 *
 * <p>{@link HandoverCodeRateLimiterTest} pins the policy; this pins the wiring.
 * The failure it guards against is quiet: a limiter that is never called, or
 * called with the wrong keys, still passes its own tests while a courier can
 * guess the code as fast as the network allows. So the assertion is on what the
 * caller sees -- the first N wrong codes are 400s and the next is a 429.
 */
class HandoverCodeThrottleTest {

    private static final int DELIVERY_LIMIT = 5;
    private static final String WRONG_CODE = "000000";

    private DeliveryRepository repository;
    private DeliveryProofService proofService;
    private HandoverCodeRateLimiter limiter;
    private DeliveryService service;

    private UUID deliveryId;
    private Delivery delivery;
    private final User courier = new User("courier@swift.com", "x",
            com.delivery.backend.modules.auth.Role.LIVREUR);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        deliveryId = UUID.randomUUID();
        courier.setId(UUID.randomUUID());

        delivery = new Delivery("parcel", "pickup", "dropoff",
                new User("client@swift.com", "x", com.delivery.backend.modules.auth.Role.CLIENT));
        delivery.setId(deliveryId);
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        delivery.setCourier(courier);
        delivery.setDropoffLat(33.5731);
        delivery.setDropoffLng(-7.5898);

        repository = mock(DeliveryRepository.class);
        when(repository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        proofService = mock(DeliveryProofService.class);
        when(proofService.verifyHandoverCode(eq(deliveryId), anyString())).thenReturn(false);

        limiter = new HandoverCodeRateLimiter(DELIVERY_LIMIT, 20, Duration.ofMinutes(15),
                Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC));

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
                limiter,
                mock(SealChainHeadRepository.class),
                mock(SealTombstoneRepository.class));
    }

    private void confirmWithWrongCode() {
        service.updateDeliveryStatus(deliveryId, DeliveryStatus.DELIVERED,
                33.5731, -7.5898, WRONG_CODE, courier);
    }

    @Test
    @DisplayName("wrong codes are rejected, and the next one is a 429, not a 400")
    void wrongCodesAreThrottled() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            assertThrows(IllegalArgumentException.class, this::confirmWithWrongCode,
                    "wrong code " + (i + 1) + " should be a plain rejection");
        }

        // The delivery's budget is spent: the next attempt never reaches the
        // verifier. If the limiter were not wired in, this would still be a 400
        // (invalid code) forever, which is the brute-force hole in the roadmap.
        assertThrows(RateLimitExceededException.class, this::confirmWithWrongCode,
                "after the delivery limit, guessing must be refused with a 429");
    }

    @Test
    @DisplayName("the throttle is per delivery: another order still gets its own tries")
    void anotherDeliveryIsNotBlockedByTheFirst() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            assertThrows(IllegalArgumentException.class, this::confirmWithWrongCode);
        }
        assertThrows(RateLimitExceededException.class, this::confirmWithWrongCode);

        // A second, untouched delivery owned by the same courier. The courier
        // counter (20) is not exhausted, so this must stay a 400, not a 429.
        UUID otherId = UUID.randomUUID();
        Delivery other = new Delivery("parcel2", "p", "d",
                new User("client2@swift.com", "x", com.delivery.backend.modules.auth.Role.CLIENT));
        other.setId(otherId);
        other.setStatus(DeliveryStatus.IN_TRANSIT);
        other.setCourier(courier);
        other.setDropoffLat(33.5731);
        other.setDropoffLng(-7.5898);
        when(repository.findById(otherId)).thenReturn(Optional.of(other));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateDeliveryStatus(otherId, DeliveryStatus.DELIVERED,
                        33.5731, -7.5898, WRONG_CODE, courier),
                "an exhausted delivery must not lock the courier out of a fresh one");
    }
}
