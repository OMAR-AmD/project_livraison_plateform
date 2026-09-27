package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.delivery.dto.DeliveryQuoteResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.LocalTime;

/**
 * Single source of truth for delivery pricing.
 *
 * <p>Historically the price was computed in three places that disagreed: the browser
 * showed the client a figure derived from the coordinates they picked, the server
 * stored a figure derived from randomly generated coordinates, and both used a
 * straight-line haversine distance while the product was documented as being priced
 * on the OSRM road route. The amount displayed at checkout could therefore differ
 * from the amount recorded against the order.
 *
 * <p>This service removes the ambiguity: the server quotes from the OSRM road route,
 * and the figure the client is shown is the figure that gets stored.
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /** Flat fare applied to every delivery. */
    public static final double BASE_FARE_MAD = 15.0;

    /** Per-kilometre rate on top of the base fare. */
    public static final double PER_KM_MAD = 5.0;

    /**
     * The delivery promise the platform makes to clients, and the same bound the
     * auto-dispatcher enforces when it builds a courier route. Keeping the two in
     * one constant prevents the dispatcher from accepting work the booking form
     * already refused.
     */
    public static final int MAX_TRANSIT_SECONDS = 45 * 60;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String osrmBaseUrl;

    public PricingService(
            ObjectMapper objectMapper,
            @Value("${app.osrm.base-url:http://localhost:5000}") String osrmBaseUrl) {
        this.objectMapper = objectMapper;
        this.osrmBaseUrl = osrmBaseUrl;
        // Explicit timeouts: without them a stalled OSRM leaves the caller blocked
        // indefinitely, and this call sits on the request path of every new booking.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Quotes a delivery from its pickup and dropoff coordinates.
     *
     * @throws IllegalArgumentException if any coordinate is missing
     * @throws IllegalStateException    if OSRM cannot be reached
     */
    public DeliveryQuoteResponse quote(Double pickupLat, Double pickupLng,
                                      Double dropoffLat, Double dropoffLng) {
        requireCoordinate(pickupLat, pickupLng, "pickup");
        requireCoordinate(dropoffLat, dropoffLng, "dropoff");

        String url = String.format("%s/route/v1/driving/%f,%f;%f,%f?overview=false",
                osrmBaseUrl, pickupLng, pickupLat, dropoffLng, dropoffLat);

        JsonNode route;
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) {
                throw new IllegalStateException("OSRM returned no route between the two locations.");
            }
            route = routes.get(0);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Deliberately fail rather than silently substituting a straight-line
            // estimate. Inventing a distance here is exactly what made the previous
            // pricing untrustworthy, and a wrong quote is worse than a visible failure.
            log.error("OSRM quote failed for {},{} -> {},{}: {}",
                    pickupLat, pickupLng, dropoffLat, dropoffLng, e.getMessage());
            throw new IllegalStateException(
                    "The routing engine is unavailable, so this delivery cannot be priced right now. Please try again shortly.");
        }

        double distanceMeters = route.path("distance").asDouble();
        double rawDuration = route.path("duration").asDouble();

        // Shared with the auto-dispatcher so the promise made at booking and the
        // plan the solver builds are measured on the same scale.
        double congestionFactor = TrafficModel.currentFactor();
        long durationSeconds = (long) Math.ceil(rawDuration * congestionFactor);

        double distanceKm = distanceMeters / 1000.0;
        double price = Math.max(BASE_FARE_MAD, Math.ceil(distanceKm * PER_KM_MAD));

        DeliveryQuoteResponse quote = new DeliveryQuoteResponse();
        quote.setDistanceKm(round2(distanceKm));
        quote.setDurationSeconds(durationSeconds);
        quote.setPrice(price);
        quote.setCongestionFactor(congestionFactor);
        quote.setExceedsTimeLimit(durationSeconds > MAX_TRANSIT_SECONDS);
        quote.setMaxDurationSeconds(MAX_TRANSIT_SECONDS);
        return quote;
    }

    private void requireCoordinate(Double lat, Double lng, String label) {
        if (lat == null || lng == null) {
            throw new IllegalArgumentException(
                    "A valid GPS coordinate is required for the " + label + " location.");
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
