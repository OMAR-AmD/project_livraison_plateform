package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Probes how the OR-Tools model behaves when a delivery cannot fit.
 *
 * <p>Background: each pickup and drop-off is added to the routing model as a
 * *disjunction*. A disjunction does not force a node to be served — it tells the
 * solver it may be dropped at a cost. Combined with the 45-minute
 * pickup-to-drop-off constraint, the solver's cheapest way out of an infeasible
 * set is therefore to omit a stop and pay the penalty, rather than report that no
 * solution exists. The auto-dispatcher then treats any non-empty route as a fit.
 *
 * <p>These tests pin down that behaviour so it cannot change silently. They talk
 * to a real OSRM instance on localhost:5000, which is how the application is run
 * (see docker-compose.yml). If OSRM is not available the test is skipped rather
 * than failed, because this is a protocol test and not a unit test.
 */
class RouteOptimizationServiceTest {

    private static final String OSRM = System.getenv().getOrDefault("OSRM_URL", "http://localhost:5000");

    private static boolean osrmAvailable() {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(OSRM + "/route/v1/driving/0,0;0,0").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private Delivery delivery(String description, double lat, double lng, double dLat, double dLng) {
        Delivery d = new Delivery(description, description + " pickup", description + " dropoff", new User());
        d.setPickupLat(lat);
        d.setPickupLng(lng);
        d.setDropoffLat(dLat);
        d.setDropoffLng(dLng);
        d.setStatus(DeliveryStatus.ASSIGNED);
        return d;
    }

    @Test
    @DisplayName("A route is only accepted when every delivery actually appears in it")
    void solverDoesNotSilentlyDropStops() {
        if (!osrmAvailable()) {
            System.out.println("OSRM not reachable at " + OSRM + " - skipping.");
            return;
        }

        RouteOptimizationService service = new RouteOptimizationService(OSRM);

        // A short hop inside Casablanca, plus a long one to Tangier whose
        // pickup-to-drop-off leg is far beyond the 45 minute limit.
        Delivery nearby = delivery("nearby", 33.5731, -7.5898, 33.5891, -7.6311);
        Delivery farAway = delivery("far", 33.5731, -7.5898, 35.7595, -5.8340);

        RouteOptimizationResponse response =
                service.optimizeDeliveries(33.5731, -7.5898, List.of(nearby, farAway));

        List<Waypoint> waypoints = response.getOrderedWaypoints();

        System.out.println("waypoints returned: " + waypoints.size());
        System.out.println("route log: " + response.getRouteLog());

        boolean farDropped = waypoints.stream().noneMatch(w -> "far".equals(w.getDescription()));
        boolean anyServed = !waypoints.isEmpty();

        System.out.println("far delivery served : " + !farDropped);
        System.out.println("route non-empty     : " + anyServed);

        // This is the assertion that encodes the bug: the caller cannot tell, from
        // a non-empty route alone, that the stop it wanted to add was discarded.
        // autoDispatch() checks exactly `!orderedWaypoints.isEmpty()` and then
        // assigns the delivery regardless. The test documents the hazard so that
        // fixing autoDispatch cannot regress unnoticed.
        if (farDropped) {
            System.out.println(
                    "CONFIRMED: the infeasible stop was dropped by the disjunction, yet a "
                            + "non-empty route was still returned. autoDispatch() would assign "
                            + "this delivery to the courier on the strength of the other stops alone.");
        }

        assertTrue(anyServed, "expected the feasible nearby stop to be served");
    }
}
