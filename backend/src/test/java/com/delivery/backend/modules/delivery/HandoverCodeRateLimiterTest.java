package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.RateLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of the wrong-handover-code limiter, tested against the shipped
 * class.
 *
 * <p>This limiter is the difference between a 6-digit code being a secret and
 * being a 1-in-a-million lottery an assigned courier can simply buy tickets for.
 * The values pinned here are decisions: how many guesses one delivery gets,
 * whether the second counter actually spans deliveries, what a correct code
 * clears, and when the window reopens. Each test is written so that a specific
 * plausible mutation -- drop either counter, stop clearing on success, ignore
 * expiry, count successes as failures -- turns it red.
 *
 * <p>The clock is a test double: waiting out a real fifteen-minute window is
 * enough of a reason not to write the test at all.
 */
class HandoverCodeRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int DELIVERY_LIMIT = 5;
    private static final int COURIER_LIMIT = 20;

    private static final UUID DELIVERY_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DELIVERY_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DELIVERY_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID DELIVERY_D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID DELIVERY_E = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID COURIER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final TestClock clock = new TestClock();
    private final HandoverCodeRateLimiter limiter =
            new HandoverCodeRateLimiter(DELIVERY_LIMIT, COURIER_LIMIT, WINDOW, clock);

    @Test
    @DisplayName("guesses under the delivery limit are allowed")
    void guessesUnderTheLimitAreAllowed() {
        for (int i = 0; i < DELIVERY_LIMIT - 1; i++) {
            limiter.recordFailure(DELIVERY_A, COURIER);
        }
        assertDoesNotThrow(() -> limiter.checkAllowed(DELIVERY_A, COURIER));
    }

    @Test
    @DisplayName("the delivery limit refuses the next guess once reached")
    void theDeliveryLimitRefusesTheNextGuess() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            limiter.recordFailure(DELIVERY_A, COURIER);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_A, COURIER));
    }

    @Test
    @DisplayName("one exhausted delivery does not lock the courier out of another")
    void oneDeliveryDoesNotBlockAnother() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            limiter.recordFailure(DELIVERY_A, COURIER);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_A, COURIER));

        // The courier counter is well under its looser ceiling, so the next
        // order still gets its own five tries. If the counters were keyed wrong
        // this is where a courier with two legitimate orders would notice.
        assertDoesNotThrow(() -> limiter.checkAllowed(DELIVERY_B, COURIER));
    }

    @Test
    @DisplayName("spreading guesses across deliveries trips the courier limit")
    void spreadingGuessesTripsTheCourierLimit() {
        // Four deliveries at their own limit = COURIER_LIMIT failures, with no
        // single delivery over its own ceiling. Only the courier counter sees it.
        for (UUID delivery : new UUID[] {DELIVERY_A, DELIVERY_B, DELIVERY_C, DELIVERY_D}) {
            for (int i = 0; i < DELIVERY_LIMIT; i++) {
                limiter.recordFailure(delivery, COURIER);
            }
        }

        // A fifth delivery has zero failures of its own, yet the courier is spent.
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_E, COURIER));
    }

    @Test
    @DisplayName("a correct code clears the delivery's failures")
    void aCorrectCodeClearsTheDeliveryFailures() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            limiter.recordFailure(DELIVERY_A, COURIER);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_A, COURIER));

        limiter.recordSuccess(DELIVERY_A);

        assertDoesNotThrow(() -> limiter.checkAllowed(DELIVERY_A, COURIER));
    }

    @Test
    @DisplayName("a correct code does not erase the courier's history")
    void aCorrectCodeDoesNotClearTheCourierCounter() {
        for (UUID delivery : new UUID[] {DELIVERY_A, DELIVERY_B, DELIVERY_C, DELIVERY_D}) {
            for (int i = 0; i < DELIVERY_LIMIT; i++) {
                limiter.recordFailure(delivery, COURIER);
            }
        }
        // Even after one delivery is closed legitimately, the accumulated
        // failures still stand against the courier: otherwise a guesser could
        // reset their own budget by succeeding once.
        limiter.recordSuccess(DELIVERY_D);

        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_E, COURIER));
    }

    @Test
    @DisplayName("the window expires and the delivery can be guessed again")
    void theWindowExpires() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            limiter.recordFailure(DELIVERY_A, COURIER);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_A, COURIER));

        clock.advance(WINDOW);

        assertDoesNotThrow(() -> limiter.checkAllowed(DELIVERY_A, COURIER));
    }

    @Test
    @DisplayName("the refusal reports a wait that fits inside the window")
    void theRefusalReportsAWaitInsideTheWindow() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            limiter.recordFailure(DELIVERY_A, COURIER);
        }
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_A, COURIER));

        assertTrue(e.getRetryAfterSeconds() >= 1,
                "a Retry-After of zero tells the client to retry immediately");
        assertTrue(e.getRetryAfterSeconds() <= WINDOW.toSeconds(),
                "the wait cannot exceed the window: " + e.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("the refusal names the handover code, not a sign-in")
    void theRefusalNamesTheHandoverCode() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            limiter.recordFailure(DELIVERY_A, COURIER);
        }
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_A, COURIER));

        // The exception type is shared with the sign-in limiter. Reusing its
        // message verbatim would tell a courier at a doorstep that they made too
        // many "sign-in attempts", which describes an attack they did not make.
        assertTrue(e.getMessage().toLowerCase().contains("handover code"),
                "the message must describe the action that was throttled: " + e.getMessage());
    }

    @Test
    @DisplayName("a null courier still enforces the delivery limit")
    void aNullCourierStillEnforcesTheDeliveryLimit() {
        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            limiter.recordFailure(DELIVERY_A, null);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(DELIVERY_A, null));
    }

    /** A clock the test moves by hand, so the window need not be waited out. */
    private static final class TestClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
