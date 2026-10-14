package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.delivery.dto.DeliveryQuoteResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quoting without a prepared map: the jury question is what happens on a
 * laptop where OSRM was never fed the extract.
 *
 * <p>{@code createDelivery} prices every order through here, so a dead routing
 * engine does not just degrade dispatch — nobody can book. The quote therefore
 * tries the configured engine, then the public demo server, and only then
 * fails honestly. These tests run that chain against in-JDK fake servers, so
 * no test traffic ever touches the real public endpoint.
 */
class PricingServiceTest {

    private static final String ROUTE_JSON =
            "{\"routes\":[{\"distance\":2500.0,\"duration\":300.0}],\"code\":\"Ok\"}";

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    /** A fake OSRM answering every route request with a fixed 2.5 km route. */
    private String fakeOsrm() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ROUTE_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A port nothing listens on: connection refused, fast. */
    private static String deadPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("the configured engine prices the order when it answers")
    void primaryPricesWhenAvailable() throws Exception {
        PricingService pricing = new PricingService(new ObjectMapper(), fakeOsrm(), fakeOsrm());

        DeliveryQuoteResponse quote = pricing.quote(33.5731, -7.5898, 33.5891, -7.6311);

        assertEquals(2.5, quote.getDistanceKm());
        assertEquals(15.0, quote.getPrice(), "2.5 km at 5 MAD/km stays under the 15 MAD floor");
        assertEquals((long) Math.ceil(300.0 * TrafficModel.currentFactor()), quote.getDurationSeconds());
        assertFalse(quote.isExceedsTimeLimit());
    }

    @Test
    @DisplayName("a dead primary falls back to the second engine instead of refusing the booking")
    void deadPrimaryFallsBack() throws Exception {
        PricingService pricing = new PricingService(new ObjectMapper(), deadPort(), fakeOsrm());

        DeliveryQuoteResponse quote = pricing.quote(33.5731, -7.5898, 33.5891, -7.6311);

        assertEquals(2.5, quote.getDistanceKm());
        assertEquals(15.0, quote.getPrice());
    }

    @Test
    @DisplayName("two dead engines fail honestly, never with an invented price")
    void twoDeadEnginesFailHonestly() throws Exception {
        PricingService pricing = new PricingService(new ObjectMapper(), deadPort(), deadPort());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> pricing.quote(33.5731, -7.5898, 33.5891, -7.6311));
        assertTrue(e.getMessage().contains("unavailable"),
                "the failure must say the engine is down, not invent a distance: " + e.getMessage());
    }

    @Test
    @DisplayName("a blank fallback disables the second tier")
    void blankFallbackDisablesTheSecondTier() throws Exception {
        PricingService pricing = new PricingService(new ObjectMapper(), deadPort(), "");

        assertThrows(IllegalStateException.class,
                () -> pricing.quote(33.5731, -7.5898, 33.5891, -7.6311));
    }
}
