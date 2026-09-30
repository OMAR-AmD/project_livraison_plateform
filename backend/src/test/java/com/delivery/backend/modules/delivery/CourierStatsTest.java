package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins down how a courier's headline numbers are derived from their deliveries.
 *
 * <p>There is no courier profile table, so everything shown to a courier and to a
 * dispatcher about them is computed from the delivery rows. That makes two
 * mistakes easy and both are invisible without a test:
 *
 * <ol>
 *   <li>Averaging over every delivery instead of the rated ones. A courier with a
 *       single 5-star review and two orders nobody has rated yet would be shown
 *       1.7 — this exact bug shipped once, which is why it is pinned here.</li>
 *   <li>Reporting a courier with no ratings as 0.0. Zero is a legitimate (bad)
 *       score, so "no data" has to stay distinguishable from "rated zero".</li>
 * </ol>
 */
class CourierStatsTest {

    private DeliveryService serviceReturning(List<Delivery> deliveries) {
        DeliveryRepository repository = mock(DeliveryRepository.class);
        when(repository.findByCourierOrderByCreatedAtDesc(any(User.class))).thenReturn(deliveries);

        // The other constructor dependencies are never touched by
        // getCourierStats, so nulls are safe and keep the test free of Spring.
        return new DeliveryService(repository, null, null, null, null, null, null, null, null);
    }

    private Delivery delivery(DeliveryStatus status, Integer rating) {
        Delivery d = new Delivery("parcel", "pickup", "dropoff", new User());
        d.setStatus(status);
        d.setRating(rating);
        return d;
    }

    @Test
    @DisplayName("unrated deliveries must not drag the average down towards zero")
    void averagesOverRatedDeliveriesOnly() {
        User courier = new User("courier1@swift.com", "x", com.delivery.backend.modules.auth.Role.LIVREUR);

        DeliveryService.CourierStatsDTO stats = serviceReturning(List.of(
                delivery(DeliveryStatus.DELIVERED, 5),
                delivery(DeliveryStatus.DELIVERED, null),
                delivery(DeliveryStatus.IN_TRANSIT, null)
        )).getCourierStats(courier);

        assertEquals(3, stats.totalDeliveries);
        assertEquals(1, stats.ratingCount);
        // 5.0, not (5 + 0 + 0) / 3 = 1.67
        assertEquals(5.0, stats.averageRating, 0.001,
                "The average must be taken over rated deliveries only");
    }

    @Test
    @DisplayName("a courier with no ratings reports null, not 0.0")
    void noRatingsIsNullNotZero() {
        User courier = new User("courier2@swift.com", "x", com.delivery.backend.modules.auth.Role.LIVREUR);

        DeliveryService.CourierStatsDTO stats = serviceReturning(List.of(
                delivery(DeliveryStatus.ASSIGNED, null),
                delivery(DeliveryStatus.DELIVERED, null)
        )).getCourierStats(courier);

        assertEquals(2, stats.totalDeliveries);
        assertEquals(0, stats.ratingCount);
        assertNull(stats.averageRating,
                "Null distinguishes 'nothing rated yet' from a genuine zero-star score");
    }

    @Test
    @DisplayName("a courier who never received a delivery is all zeroes, still null rating")
    void brandNewCourier() {
        User courier = new User("courier3@swift.com", "x", com.delivery.backend.modules.auth.Role.LIVREUR);

        DeliveryService.CourierStatsDTO stats = serviceReturning(List.of()).getCourierStats(courier);

        assertEquals(0, stats.totalDeliveries);
        assertEquals(0, stats.completedDeliveries);
        assertEquals(0, stats.activeDeliveries);
        assertEquals(0, stats.cancelledDeliveries);
        assertNull(stats.averageRating);
    }

    @Test
    @DisplayName("status buckets are counted separately and sum to the total")
    void countsSplitByStatus() {
        User courier = new User("courier1@swift.com", "x", com.delivery.backend.modules.auth.Role.LIVREUR);

        DeliveryService.CourierStatsDTO stats = serviceReturning(List.of(
                delivery(DeliveryStatus.DELIVERED, 4),
                delivery(DeliveryStatus.DELIVERED, 5),
                delivery(DeliveryStatus.DELIVERED, 3),
                delivery(DeliveryStatus.ASSIGNED, null),
                delivery(DeliveryStatus.IN_TRANSIT, null),
                delivery(DeliveryStatus.CANCELLED, null),
                delivery(DeliveryStatus.PENDING, null)
        )).getCourierStats(courier);

        assertEquals(7, stats.totalDeliveries);
        assertEquals(3, stats.completedDeliveries);
        assertEquals(2, stats.activeDeliveries);
        assertEquals(1, stats.cancelledDeliveries);
        assertEquals(3, stats.ratingCount);
        assertEquals(4.0, stats.averageRating, 0.001);
        // A PENDING order is assigned-but-neither-taken-nor-closed, so it lands in
        // no bucket. CourierStats.js uses exactly this gap to decide whether to
        // show the "N orders assigned in total" footnote, so it must be real and
        // not hidden by reclassifying PENDING as active.
        assertEquals(6, stats.completedDeliveries + stats.activeDeliveries + stats.cancelledDeliveries,
                "PENDING must be counted in none of the three buckets, so the UI footnote triggers");
    }
}
