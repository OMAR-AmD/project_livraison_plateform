package com.delivery.backend.modules.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The in-memory store, tested directly rather than through the limiter.
 *
 * <p>{@link HandoverCodeRateLimiterTest} exercises this store through the policy,
 * and the policy is deliberately loose about the wait: the limiter clamps whatever
 * the store reports into {@code [1, window]}, so a store that returned a wrong
 * magnitude -- or zero, or a value off by the window -- would still produce a
 * plausible-looking {@code Retry-After}. A mutation pass found exactly that: the
 * return value of {@code increment} and the arithmetic in {@code retryAfterSeconds}
 * could be broken without a single policy test turning red.
 *
 * <p>So this pins the store's own contract, including the sweep no test in the
 * suite ever triggered.
 */
class InMemoryHandoverAttemptStoreTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final TestClock clock = new TestClock();
    private final InMemoryHandoverAttemptStore store = new InMemoryHandoverAttemptStore(clock);

    @Test
    @DisplayName("increment counts from one and returns the running total")
    void incrementReturnsTheRunningTotal() {
        assertEquals(1L, store.increment("k", WINDOW));
        assertEquals(2L, store.increment("k", WINDOW));
        assertEquals(3L, store.increment("k", WINDOW));
        assertEquals(3L, store.count("k"));
    }

    @Test
    @DisplayName("a counter that was never written is zero, with nothing to wait for")
    void anAbsentCounterIsZero() {
        assertEquals(0L, store.count("absent"));
        assertEquals(-1L, store.retryAfterSeconds("absent"));
    }

    @Test
    @DisplayName("retryAfterSeconds reports the time left and counts down with the clock")
    void retryAfterCountsDown() {
        store.increment("k", WINDOW);
        assertEquals(WINDOW.toSeconds(), store.retryAfterSeconds("k"));

        clock.advance(Duration.ofMinutes(5));
        assertEquals(WINDOW.toSeconds() - 300L, store.retryAfterSeconds("k"));
    }

    @Test
    @DisplayName("the counter is gone once the window passes")
    void theCounterExpires() {
        store.increment("k", Duration.ofSeconds(10));
        assertEquals(1L, store.count("k"));

        clock.advance(Duration.ofSeconds(10));

        assertEquals(0L, store.count("k"), "an expired window frees the delivery to be guessed again");
        assertEquals(-1L, store.retryAfterSeconds("k"));
    }

    @Test
    @DisplayName("clear forgets the counter immediately")
    void clearForgetsTheCounter() {
        store.increment("k", WINDOW);
        store.clear("k");
        assertEquals(0L, store.count("k"));
    }

    @Test
    @DisplayName("the periodic sweep drops expired counters, so the map cannot grow forever")
    void theSweepDropsExpiredCounters() {
        // Four writes are the sweep interval, so the fourth write is what triggers
        // it -- the store must not need a write per key to reclaim the memory.
        InMemoryHandoverAttemptStore small = new InMemoryHandoverAttemptStore(clock, 4);
        for (int i = 0; i < 3; i++) {
            small.increment("old-" + i, Duration.ofSeconds(1));
        }
        assertEquals(3, small.trackedKeys());

        clock.advance(Duration.ofSeconds(2)); // the three old counters lapse

        small.increment("fresh", WINDOW); // the fourth write sweeps

        assertEquals(1, small.trackedKeys(),
                "the expired counters must be reclaimed; only 'fresh' should remain");
    }

    /** A clock the test moves by hand, so a fifteen-minute window need not be waited out. */
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
