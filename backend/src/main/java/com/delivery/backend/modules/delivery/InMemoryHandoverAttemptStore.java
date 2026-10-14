package com.delivery.backend.modules.delivery;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single-process {@link HandoverAttemptStore}: the counters live in a map and
 * reset when the process does.
 *
 * <p>This is the store the limiter uses when it is constructed with a clock,
 * which is how its policy tests run without Redis. It is also the store for a
 * one-instance deployment that would rather not make a security control depend on
 * the cache -- the trade-off being that the counters are then per instance, so
 * two instances would each hand a guesser its own budget.
 *
 * <p>It arms the window on the first failure and drops the whole counter when the
 * window passes, exactly as the Redis store does, so the limiter cannot tell the
 * two apart.
 */
final class InMemoryHandoverAttemptStore implements HandoverAttemptStore {

    /** Expired entries are swept this often (in writes) so the map cannot grow without bound. */
    private static final int SWEEP_EVERY = 1024;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong writes = new AtomicLong();
    private final int sweepEvery;

    InMemoryHandoverAttemptStore(Clock clock) {
        this(clock, SWEEP_EVERY);
    }

    /**
     * The same store with a caller-chosen sweep interval. Only a test uses this,
     * because the production interval (every 1024 writes) is far more than a test
     * would ever perform -- which is how an untested sweep quietly rots.
     */
    InMemoryHandoverAttemptStore(Clock clock, int sweepEvery) {
        this.clock = clock;
        this.sweepEvery = sweepEvery;
    }

    @Override
    public long increment(String key, Duration window) {
        long now = clock.millis();
        long count = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(now)) {
                return new Window(now + window.toMillis(), 1);
            }
            return new Window(existing.expiresAtMillis(), existing.count() + 1);
        }).count();
        if (writes.incrementAndGet() % sweepEvery == 0) {
            sweep(now);
        }
        return count;
    }

    @Override
    public long count(String key) {
        Window window = windows.get(key);
        return window == null || window.expired(clock.millis()) ? 0L : window.count();
    }

    @Override
    public long retryAfterSeconds(String key) {
        Window window = windows.get(key);
        long now = clock.millis();
        if (window == null || window.expired(now)) {
            return -1L;
        }
        return Math.max(1L, (window.expiresAtMillis() - now + 999) / 1000);
    }

    @Override
    public void clear(String key) {
        windows.remove(key);
    }

    /** How many counters are retained. Visible for tests: the sweep is invisible otherwise. */
    int trackedKeys() {
        return windows.size();
    }

    private void sweep(long now) {
        windows.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    private record Window(long expiresAtMillis, int count) {
        boolean expired(long now) {
            return now >= expiresAtMillis;
        }
    }
}
