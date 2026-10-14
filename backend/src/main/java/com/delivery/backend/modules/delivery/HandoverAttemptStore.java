package com.delivery.backend.modules.delivery;

import java.time.Duration;

/**
 * Where the wrong-handover-code counters are kept.
 *
 * <p>Factored out of {@link HandoverCodeRateLimiter} for the same reason
 * {@code FraudTrailStore} is kept separate from {@code TrajectoryFraudService}:
 * the policy -- how many guesses a delivery gets, what a correct code clears --
 * is a pure decision that should be testable without a container, while the
 * storage is the part that genuinely needs Redis. This interface is the seam.
 *
 * <p>Two implementations ship. {@link RedisHandoverAttemptStore} is shared
 * across instances and is what the deployed platform uses;
 * {@link InMemoryHandoverAttemptStore} is a single-process map, used by the
 * limiter when it is built with a clock (the policy tests, and a one-instance
 * run that would rather not depend on Redis for a security control).
 *
 * <p>Every method may throw {@link AttemptStoreUnavailableException} when the
 * store cannot be reached. The limiter turns that into a refusal rather than a
 * bypass: a throttle that silently stops throttling when its backing store is
 * down is a security control with an off switch.
 */
public interface HandoverAttemptStore {

    /**
     * Counts one failure against {@code key} and returns the new total. The first
     * increment for a key also arms its {@code window}, so a counter can never be
     * left without an expiry -- an immortal key would be a lockout that never
     * lifts.
     *
     * <p>Must be atomic: two instances counting the same key at once must not
     * lose an increment, which is the entire point of moving this out of a
     * per-instance map.
     */
    long increment(String key, Duration window);

    /** The failures recorded against {@code key} inside its window, or 0. */
    long count(String key);

    /** Whole seconds until {@code key} expires, or -1 when there is no live counter. */
    long retryAfterSeconds(String key);

    /** Forgets {@code key} immediately -- a correct code clears its delivery. */
    void clear(String key);
}
