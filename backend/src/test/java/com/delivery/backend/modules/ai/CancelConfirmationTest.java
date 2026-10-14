package com.delivery.backend.modules.ai;

import com.delivery.backend.modules.auth.Role;
import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.auth.UserRepository;
import com.delivery.backend.modules.delivery.dto.DeliveryResponse;
import com.delivery.backend.modules.delivery.DeliveryService;
import com.delivery.backend.modules.delivery.DeliveryStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancelling takes two tool calls with an explicit "yes" between them, and the
 * backend — not the prompt — enforces the order.
 *
 * <p>The 3B model proved the prompt alone is not enough: "annule-la" with two
 * live orders cancelled one of them outright. So proposing records intent and
 * changes nothing, and confirming executes only a live, matching proposal.
 * Every refusal path below also asserts the order was left alone, because a
 * refusal that mutates is not a refusal.
 */
class CancelConfirmationTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private final User client = user();
    private final DeliveryService deliveryService = mock(DeliveryService.class);

    private static User user() {
        User user = new User("client@swift.com", "x", Role.CLIENT);
        user.setId(UUID.randomUUID());
        return user;
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(client, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private DeliveryResponse pendingOrder(UUID id) {
        DeliveryResponse response = mock(DeliveryResponse.class);
        when(response.getStatus()).thenReturn(DeliveryStatus.PENDING);
        when(deliveryService.getDeliveryForClient(eq(id), eq(client))).thenReturn(response);
        return response;
    }

    // ---------------------------------------------------------- the store

    @Test
    @DisplayName("a proposal is consumed exactly once")
    void proposalIsConsumedOnce() {
        UUID clientId = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));

        store.propose(clientId, order);

        assertEquals(order, store.consume(clientId, order).orElse(null));
        assertTrue(store.consume(clientId, order).isEmpty(), "a spent intent cannot be replayed");
    }

    @Test
    @DisplayName("confirming nothing, or the wrong order, refuses and keeps a live proposal")
    void wrongConfirmationsRefuse() {
        UUID clientId = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));

        assertTrue(store.consume(clientId, order).isEmpty(), "no proposal, no execution");

        store.propose(clientId, order);
        assertTrue(store.consume(clientId, UUID.randomUUID()).isEmpty(),
                "a confused model cannot spend the intent on another order");
        assertEquals(order, store.consume(clientId, order).orElse(null),
                "the live proposal survives the refused attempt");
    }

    @Test
    @DisplayName("proposals lapse after ten minutes")
    void proposalsLapse() {
        MutableClock clock = new MutableClock();
        CancelProposalStore store = new CancelProposalStore(clock);
        UUID clientId = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        store.propose(clientId, order);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertTrue(store.consume(clientId, order).isEmpty(), "a stale yes is not consent");
        assertEquals(0, store.pendingCount());
    }

    // ---------------------------------------------------------- propose

    @Test
    @DisplayName("proposing without authentication is refused before anything is read")
    void proposeWithoutAuthentication() {
        AiConfiguration ai = new AiConfiguration();
        Function<AiConfiguration.ProposeCancellationRequest, AiConfiguration.ProposeCancellationResponse> propose =
                ai.proposeCancellationFunction(deliveryService, new CancelProposalStore());

        AiConfiguration.ProposeCancellationResponse response =
                propose.apply(new AiConfiguration.ProposeCancellationRequest(UUID.randomUUID().toString()));

        assertEquals("ERROR", response.status());
        verify(deliveryService, never()).getDeliveryForClient(any(), any());
    }

    @Test
    @DisplayName("proposing records intent but changes nothing")
    void proposeRecordsWithoutMutating() {
        authenticate();
        AiConfiguration ai = new AiConfiguration();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));
        UUID order = UUID.randomUUID();
        pendingOrder(order);

        AiConfiguration.ProposeCancellationResponse response = ai
                .proposeCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ProposeCancellationRequest(order.toString()));

        assertEquals("PROPOSED", response.status());
        assertEquals(1, store.pendingCount());
        verify(deliveryService, never()).cancelDelivery(any(), any());
    }

    @Test
    @DisplayName("proposing an unknown, foreign or closed order explains instead of recording")
    void proposeRejectsBadTargets() {
        authenticate();
        AiConfiguration ai = new AiConfiguration();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));
        UUID unknown = UUID.randomUUID();
        when(deliveryService.getDeliveryForClient(eq(unknown), eq(client)))
                .thenThrow(new IllegalArgumentException("Delivery not found"));
        UUID closed = UUID.randomUUID();
        DeliveryResponse delivered = mock(DeliveryResponse.class);
        when(delivered.getStatus()).thenReturn(DeliveryStatus.DELIVERED);
        when(deliveryService.getDeliveryForClient(eq(closed), eq(client))).thenReturn(delivered);

        assertEquals("ERROR", ai.proposeCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ProposeCancellationRequest(unknown.toString())).status());
        assertEquals("ERROR", ai.proposeCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ProposeCancellationRequest(closed.toString())).status());
        assertEquals("ERROR", ai.proposeCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ProposeCancellationRequest("  ")).status());
        assertEquals(0, store.pendingCount(), "a refused proposal must not linger");
        verify(deliveryService, never()).cancelDelivery(any(), any());
    }

    // ---------------------------------------------------------- confirm

    @Test
    @DisplayName("confirming executes a live matching proposal exactly once")
    void confirmExecutesOnce() {
        authenticate();
        AiConfiguration ai = new AiConfiguration();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));
        UUID order = UUID.randomUUID();
        pendingOrder(order);
        store.propose(client.getId(), order);

        AiConfiguration.ConfirmCancellationResponse first = ai
                .confirmCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ConfirmCancellationRequest(order.toString()));

        assertEquals("SUCCESS", first.status());
        verify(deliveryService, times(1)).cancelDelivery(order, client);

        AiConfiguration.ConfirmCancellationResponse second = ai
                .confirmCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ConfirmCancellationRequest(order.toString()));
        assertEquals("ERROR", second.status(), "the intent was spent");
        verify(deliveryService, times(1)).cancelDelivery(any(), any());
    }

    @Test
    @DisplayName("confirming without proposing, or another order, refuses without mutating")
    void confirmWithoutProposalRefuses() {
        authenticate();
        AiConfiguration ai = new AiConfiguration();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));
        UUID order = UUID.randomUUID();
        pendingOrder(order);

        assertEquals("ERROR", ai.confirmCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ConfirmCancellationRequest(order.toString())).status());

        store.propose(client.getId(), order);
        assertEquals("ERROR", ai.confirmCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ConfirmCancellationRequest(UUID.randomUUID().toString())).status());
        verify(deliveryService, never()).cancelDelivery(any(), any());
    }

    @Test
    @DisplayName("a bare yes confirms the pending proposal, and nothing without one")
    void bareYesConfirmsPending() {
        authenticate();
        AiConfiguration ai = new AiConfiguration();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));
        UUID order = UUID.randomUUID();
        pendingOrder(order);

        AiConfiguration.ConfirmCancellationResponse refused = ai
                .confirmCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ConfirmCancellationRequest(null));
        assertEquals("ERROR", refused.status());
        verify(deliveryService, never()).cancelDelivery(any(), any());

        store.propose(client.getId(), order);
        AiConfiguration.ConfirmCancellationResponse done = ai
                .confirmCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ConfirmCancellationRequest("  "));
        assertEquals("SUCCESS", done.status());
        verify(deliveryService, times(1)).cancelDelivery(order, client);
    }

    @Test
    @DisplayName("an order closed between propose and confirm is refused, not cancelled late")
    void closedSinceProposeIsRefused() {
        authenticate();
        AiConfiguration ai = new AiConfiguration();
        CancelProposalStore store = new CancelProposalStore(Clock.fixed(T0, ZoneOffset.UTC));
        UUID order = UUID.randomUUID();
        pendingOrder(order);
        store.propose(client.getId(), order);
        when(deliveryService.cancelDelivery(eq(order), eq(client)))
                .thenThrow(new IllegalArgumentException("You can only cancel a delivery while it is PENDING"));

        AiConfiguration.ConfirmCancellationResponse response = ai
                .confirmCancellationFunction(deliveryService, store)
                .apply(new AiConfiguration.ConfirmCancellationRequest(order.toString()));

        assertEquals("ERROR", response.status());
        assertTrue(response.message().contains("PENDING"));
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant instant = T0;

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
