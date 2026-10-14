package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.RateLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the limiter does when the shared counter store is not answering, and what
 * it records when it does refuse.
 *
 * <p>{@link HandoverCodeRateLimiterTest} pins the policy against the in-memory
 * store; this pins the two things that only matter once the counter lives in
 * Redis: the throttle must <b>fail closed</b> during an outage -- a refusal, not
 * a free pass -- and every refusal must reach the audit trail, because a lockout
 * nobody can see is a brute force nobody stops.
 */
class HandoverCodeRateLimiterFailClosedTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int DELIVERY_LIMIT = 5;
    private static final int COURIER_LIMIT = 20;

    private static final UUID DELIVERY = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID COURIER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final HandoverAttemptStore store = mock(HandoverAttemptStore.class);
    private final HandoverLockoutAudit audit = mock(HandoverLockoutAudit.class);
    private final HandoverCodeRateLimiter limiter =
            new HandoverCodeRateLimiter(store, audit, DELIVERY_LIMIT, COURIER_LIMIT, WINDOW);

    @Test
    @DisplayName("an unreachable store refuses the guess instead of waving it through")
    void checkRefusesWhenTheStoreIsDown() {
        when(store.count(anyString())).thenThrow(new AttemptStoreUnavailableException("redis down"));

        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY, COURIER),
                "a store outage must fail closed: the guesser must not get through unthrottled");

        assertTrue(e.getRetryAfterSeconds() >= 1, "a Retry-After of zero invites a retry storm");
        assertTrue(e.getRetryAfterSeconds() <= WINDOW.toSeconds(),
                "the wait cannot exceed the window: " + e.getRetryAfterSeconds());
        verify(audit).failClosed(anyString(), eq(DELIVERY.toString()), any(Throwable.class));
    }

    @Test
    @DisplayName("a failure that cannot be stored is logged, and the next check still fails closed")
    void recordFailureSurvivesAnOutageButTheNextCheckRefuses() {
        doThrow(new AttemptStoreUnavailableException("redis down"))
                .when(store).increment(anyString(), any(Duration.class));

        assertDoesNotThrow(() -> limiter.recordFailure(DELIVERY, COURIER),
                "the caller is already answering 'invalid code'; the lost write must not become a 500");
        verify(audit).failClosed(anyString(), anyString(), any(Throwable.class));

        // The attempt after the outage is refused, so a lost write is not a way
        // through the throttle.
        when(store.count(anyString())).thenThrow(new AttemptStoreUnavailableException("redis down"));
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(DELIVERY, COURIER));
    }

    @Test
    @DisplayName("reaching the delivery ceiling is audited; one below it is not")
    void deliveryLockoutIsAuditedAtTheBoundary() {
        when(store.count(anyString())).thenReturn((long) DELIVERY_LIMIT - 1);
        assertDoesNotThrow(() -> limiter.checkAllowed(DELIVERY, COURIER),
                "limit - 1 is still under the ceiling");

        when(store.count(anyString())).thenReturn((long) DELIVERY_LIMIT);
        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(DELIVERY, COURIER),
                "the ceiling itself is refused (>=, not >)");

        verify(audit).lockedOut(eq("delivery"), eq(DELIVERY.toString()),
                eq(DELIVERY_LIMIT), eq(DELIVERY_LIMIT), anyLong());
    }

    @Test
    @DisplayName("a spent courier budget is audited even with a fresh delivery")
    void courierLockoutIsAudited() {
        // The delivery is under its own tight ceiling; the courier is at its looser
        // one. Only the courier counter can see this shape of attack.
        when(store.count("handover:delivery:" + DELIVERY)).thenReturn(1L);
        when(store.count("handover:courier:" + COURIER)).thenReturn((long) COURIER_LIMIT);

        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(DELIVERY, COURIER));

        verify(audit).lockedOut(eq("courier"), eq(COURIER.toString()),
                eq(COURIER_LIMIT), eq(COURIER_LIMIT), anyLong());
    }

    @Test
    @DisplayName("a delivery already at its ceiling is refused without reading the courier counter")
    void aMaxedDeliverySkipsTheCourierCounter() {
        // The courier read is skipped once the delivery is over its own (tighter)
        // ceiling. That is a small optimisation, but it is also the difference
        // between refusing a maxed delivery and, if the courier read happens to
        // fail, failing closed for the wrong counter.
        when(store.count("handover:delivery:" + DELIVERY)).thenReturn((long) DELIVERY_LIMIT);

        assertThrows(RateLimitExceededException.class, () -> limiter.checkAllowed(DELIVERY, COURIER));

        verify(store, never()).count("handover:courier:" + COURIER);
    }

    @Test
    @DisplayName("the wait is the counter's own remaining time, clamped to [1, window]")
    void theWaitIsTheCountersOwnTtlWithinBounds() {
        // The store is trusted to know how long is left; the limiter only keeps
        // the answer inside bounds. Each boundary here was a surviving mutant.
        assertEquals(42L, refusalWaitFor(42L), "a healthy counter reports its own remaining time");
        assertEquals(1L, refusalWaitFor(1L), "one second left is one second, not the whole window");
        assertEquals(WINDOW.toSeconds(), refusalWaitFor(0L), "zero is not a wait a client can honour");
        assertEquals(WINDOW.toSeconds(), refusalWaitFor(WINDOW.toSeconds() + 100),
                "a value somehow beyond the window is clamped to it");
    }

    @Test
    @DisplayName("clearing after a correct code that cannot reach the store is logged, not thrown")
    void recordSuccessSurvivesAnOutage() {
        doThrow(new AttemptStoreUnavailableException("redis down")).when(store).clear(anyString());

        assertDoesNotThrow(() -> limiter.recordSuccess(DELIVERY),
                "a correct code must not fail because the counter could not be cleared");

        verify(audit).failClosed(anyString(), anyString(), any(Throwable.class));
    }

    /** Drives one refused guess against a store whose counter reports {@code storeTtl} seconds left. */
    private long refusalWaitFor(long storeTtl) {
        when(store.count(anyString())).thenReturn((long) DELIVERY_LIMIT);
        when(store.retryAfterSeconds(anyString())).thenReturn(storeTtl);

        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY, COURIER));
        return e.getRetryAfterSeconds();
    }
}
