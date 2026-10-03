package com.delivery.backend.modules.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of the failed-sign-in limiter, tested against the shipped class.
 *
 * <p>The policy is small enough to state and worth pinning exactly, because
 * every value here is a decision: how many guesses an account gets, whether an
 * address can be used to spread across accounts, what a success clears, and when
 * the window reopens. Each test below is written so that a specific plausible
 * mutation -- drop the per-account check, drop the per-client check, stop
 * clearing on success, ignore expiry, flip {@code >=} to {@code >} -- turns it
 * red. A check that cannot fail would not be guarding anything.
 *
 * <p>The clock is a test double on purpose: waiting out a real fifteen-minute
 * window is enough of a reason not to write the test at all.
 */
class LoginRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int ACCOUNT_LIMIT = 5;
    private static final int CLIENT_LIMIT = 50;
    private static final String VICTIM = "victim@swift.com";

    private final TestClock clock = new TestClock();
    private final LoginRateLimiter limiter =
            new LoginRateLimiter(ACCOUNT_LIMIT, CLIENT_LIMIT, WINDOW, clock);

    @Test
    @DisplayName("attempts under the account limit are allowed")
    void attemptsUnderTheLimitAreAllowed() {
        for (int i = 0; i < ACCOUNT_LIMIT - 1; i++) {
            limiter.recordFailure("10.0.0.1", VICTIM);
        }
        assertDoesNotThrow(() -> limiter.checkAllowed("10.0.0.1", VICTIM));
    }

    @Test
    @DisplayName("the account limit refuses the next attempt after it is reached")
    void theAccountLimitRefusesTheNextAttempt() {
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            limiter.recordFailure("10.0.0.1", VICTIM);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed("10.0.0.1", VICTIM));
    }

    @Test
    @DisplayName("the account limit follows the account, not the address")
    void theAccountLimitFollowsTheAccount() {
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            limiter.recordFailure("10.0.0.1", VICTIM);
        }
        // A different address must not get a fresh budget for the same account.
        // If it did, the per-client counter would be the only defence and a
        // botnet would walk straight past it.
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed("10.0.0.2", VICTIM));
    }

    @Test
    @DisplayName("the account key is case- and whitespace-insensitive")
    void theAccountKeyIsNormalised() {
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            limiter.recordFailure("10.0.0.1", "Victim@Swift.com");
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed("10.0.0.1", " victim@swift.com "));
    }

    @Test
    @DisplayName("a successful sign-in clears the account's failures")
    void aSuccessfulSignInClearsTheAccountFailures() {
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            limiter.recordFailure("10.0.0.1", VICTIM);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed("10.0.0.1", VICTIM));

        limiter.recordSuccess(VICTIM);

        assertDoesNotThrow(() -> limiter.checkAllowed("10.0.0.1", VICTIM));
    }

    @Test
    @DisplayName("the window expires and the account can try again")
    void theWindowExpires() {
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            limiter.recordFailure("10.0.0.1", VICTIM);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed("10.0.0.1", VICTIM));

        clock.advance(WINDOW);

        assertDoesNotThrow(() -> limiter.checkAllowed("10.0.0.1", VICTIM));
    }

    @Test
    @DisplayName("many accounts failing from one address trips the client limit")
    void manyAccountsFromOneAddressTripsTheClientLimit() {
        LoginRateLimiter shared = new LoginRateLimiter(100, 3, WINDOW, clock);
        shared.recordFailure("10.0.0.9", "a@swift.com");
        shared.recordFailure("10.0.0.9", "b@swift.com");
        shared.recordFailure("10.0.0.9", "c@swift.com");

        // None of these accounts is near its own limit; only the client is.
        assertThrows(RateLimitExceededException.class,
                () -> shared.checkAllowed("10.0.0.9", "d@swift.com"));
    }

    @Test
    @DisplayName("the refusal reports a wait that fits inside the window")
    void theRefusalReportsAWaitInsideTheWindow() {
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            limiter.recordFailure("10.0.0.1", VICTIM);
        }
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed("10.0.0.1", VICTIM));

        assertTrue(e.getRetryAfterSeconds() >= 1,
                "a Retry-After of zero tells the client to retry immediately");
        assertTrue(e.getRetryAfterSeconds() <= WINDOW.toSeconds(),
                "the wait cannot exceed the window: " + e.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("a null client id still enforces the account limit")
    void aNullClientIdStillEnforcesTheAccountLimit() {
        for (int i = 0; i < ACCOUNT_LIMIT; i++) {
            limiter.recordFailure(null, VICTIM);
        }
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkAllowed(null, VICTIM));
    }

    /** A clock the test moves by hand, so the window need not be waited out. */
    private static final class TestClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
