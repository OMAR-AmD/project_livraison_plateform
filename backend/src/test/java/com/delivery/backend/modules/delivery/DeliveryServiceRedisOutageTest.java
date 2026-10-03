package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.ai.FraudTrailStore;
import com.delivery.backend.modules.ai.TrajectoryFraudService;
import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.auth.UserRepository;
import com.delivery.backend.modules.delivery.dto.DeliveryResponse;
import com.delivery.backend.modules.delivery.dto.LocationUpdateRequest;
import com.delivery.backend.modules.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Redis outage must not turn readable orders into a 500.
 *
 * <p>Regression test for the live Cloud instance. The backend was deployed in
 * frankfurt while its Render Key Value took Render's default (oregon), so the
 * internal URL was unreachable. Login kept working -- it never touches Redis --
 * which made the failure look random: only the delivery lists that happened to
 * contain an {@code IN_TRANSIT} order answered 500, because that is the one
 * status for which {@code toResponse} reads the live position. The position is
 * an enrichment; its absence is a blank marker, never a broken order list.
 */
class DeliveryServiceRedisOutageTest {

    private final User client = mock(User.class);

    private Delivery inTransit() {
        Delivery delivery = new Delivery("Parcel", "Pickup", "Dropoff", client);
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        return delivery;
    }

    @SuppressWarnings("unchecked")
    private DeliveryService service(RedisTemplate<String, String> redis, DeliveryRepository repository) {
        return new DeliveryService(
                repository,
                mock(UserRepository.class),
                mock(NotificationService.class),
                redis,
                mock(SimpMessagingTemplate.class),
                new ObjectMapper(),
                mock(RouteOptimizationService.class),
                mock(PricingService.class),
                mock(TrajectoryFraudService.class),
                mock(FraudTrailStore.class),
                mock(DeliveryProofService.class));
    }

    @Test
    @DisplayName("a Redis outage does not 500 a delivery list; the live marker is simply absent")
    void deliveryListSurvivesRedisOutage() {
        DeliveryRepository repository = mock(DeliveryRepository.class);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(repository.findByClientOrderByCreatedAtDesc(client)).thenReturn(List.of(inTransit()));

        List<DeliveryResponse> listed = assertDoesNotThrow(
                () -> service(redis, repository).getDeliveriesForClient(client),
                "an unreachable cache must not turn a readable order list into a 500");

        assertEquals(1, listed.size(), "the order is still returned");
        assertEquals(DeliveryStatus.IN_TRANSIT, listed.get(0).getStatus());
        assertNull(listed.get(0).getCourierLatitude(),
                "with no reachable cache there is no live position to report, which is acceptable");
    }

    @Test
    @DisplayName("a reachable cache still serves the live marker, so the guard is not a blanket mute")
    void liveMarkerStillServedWhenCacheIsReachable() throws Exception {
        DeliveryRepository repository = mock(DeliveryRepository.class);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        String json = new ObjectMapper().writeValueAsString(new LocationUpdateRequest(33.5731, -7.5898));
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(json);
        when(repository.findByClientOrderByCreatedAtDesc(client)).thenReturn(List.of(inTransit()));

        List<DeliveryResponse> listed = service(redis, repository).getDeliveriesForClient(client);

        assertEquals(33.5731, listed.get(0).getCourierLatitude(), 1e-9,
                "with Redis up the position must still flow through");
    }
}
