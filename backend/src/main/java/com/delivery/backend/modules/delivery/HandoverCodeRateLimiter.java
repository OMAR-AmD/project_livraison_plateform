package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Throttles guesses at a delivery's 6-digit handover code.
 *
 * <p><b>Why this exists.</b> The handover code is the one path where a courier
 * can close a delivery without the client's device present. The code is only
 * six digits, so it can be found by trying: does 000000 work? 000001? Left
 * unrestricted that is a million guesses, which is minutes of scripted requests
 * and no rate limit to stop them. The floor for a legitimate client is one
 * attempt -- a typo, then a second. Everything above that has to be bounded.
 *
 * <p><b>Two counters, for two shapes of attack.</b>
 *
 * <ul>
 *   <li><b>per delivery</b> -- the tight one. Every successful guess closes the
 *   order, so the guesser is always aimed at one delivery, and 5 failures per
 *   window puts the 1-in-a-million code far out of reach.</li>
 *   <li><b>per courier</b> -- a looser second axis, because an attacker with a
 *   stolen courier account can move to the next delivery after exhausting the
 *   first. On its own the per-delivery counter would never notice, since each
 *   delivery sees only a handful of attempts.</li>
 * </ul>
 *
 * <p>Only <i>failures</i> are counted, and a correct code clears the delivery's
 * counter. Counting every attempt would let a courier who types the code wrong
 * once and then right lock themselves out of their next order, and the door is
 * exactly where a mistyped digit is expected.
 *
 * <p><b>The counters live in a {@link HandoverAttemptStore}, not in this
 * class.</b> The policy above is a pure decision; the storage is the part that
 * has to be shared. Splitting them lets this class be tested against a clock and
 * a map, while the deployed platform uses the Redis store so that two instances
 * give a guesser one budget instead of one each.
 *
 * <p>When the store cannot be reached the throttle <b>fails closed</b>: it
 * refuses the attempt and records why. A limiter that lets traffic through when
 * its backing store is down is a security control with an off switch, and this is
 * the one control standing between a courier and the client's closure code. Every
 * lockout, and every fail-closed refusal, is written to
 * {@link HandoverLockoutAudit}.
 */
@Component
public class HandoverCodeRateLimiter {

    private final int maxPerDelivery;
    private final int maxPerCourier;
    private final Duration window;
    private final HandoverAttemptStore store;
    private final HandoverLockoutAudit audit;

    /**
     * The constructor Spring uses. Annotated because there are two more, both for
     * tests: with more than one candidate Spring cannot choose, and the
     * application refuses to start.
     */
    @Autowired
    public HandoverCodeRateLimiter(
            HandoverAttemptStore store,
            HandoverLockoutAudit audit,
            @Value("${application.security.handover-rate-limit.max-per-delivery:5}") int maxPerDelivery,
            @Value("${application.security.handover-rate-limit.max-per-courier:20}") int maxPerCourier,
            @Value("${application.security.handover-rate-limit.window-seconds:900}") long windowSeconds) {
        this(store, audit, maxPerDelivery, maxPerCourier, Duration.ofSeconds(windowSeconds));
    }

    /**
     * Tests (and a single-process run): a map-backed store driven by a clock they
     * can move, so a fifteen-minute window need not be waited out.
     */
    HandoverCodeRateLimiter(int maxPerDelivery, int maxPerCourier, Duration window, Clock clock) {
        this(new InMemoryHandoverAttemptStore(clock), new HandoverLockoutAudit(),
                maxPerDelivery, maxPerCourier, window);
    }

    HandoverCodeRateLimiter(HandoverAttemptStore store, HandoverLockoutAudit audit,
                            int maxPerDelivery, int maxPerCourier, Duration window) {
        this.store = store;
        this.audit = audit;
        this.maxPerDelivery = maxPerDelivery;
        this.maxPerCourier = maxPerCourier;
        this.window = window;
    }

    /**
     * Refuses the attempt if either counter is already exhausted. Called before
     * the code is compared, so a blocked guesser costs no work and gets no signal
     * about whether the code was close.
     *
     * @throws RateLimitExceededException when the caller must wait, and also when
     *         the counter store is unreachable -- the throttle fails closed rather
     *         than letting an unthrottled guess through
     */
    public void checkAllowed(UUID deliveryId, UUID courierId) {
        String delivery = deliveryKey(deliveryId);
        String courier = courierId == null ? null : courierKey(courierId);

        long deliveryCount;
        long courierCount = 0L;
        try {
            deliveryCount = store.count(delivery);
            // Only spend a second round trip on the courier when the delivery is
            // not already over its own (tighter) ceiling.
            if (deliveryCount < maxPerDelivery && courier != null) {
                courierCount = store.count(courier);
            }
        } catch (AttemptStoreUnavailableException e) {
            long wait = Math.max(1L, window.toSeconds());
            audit.failClosed("read the wrong-code counters", deliveryId.toString(), e);
            throw new RateLimitExceededException(
                    "Handover-code checks are temporarily unavailable. Try again in "
                            + wait + " seconds.", wait);
        }

        if (deliveryCount >= maxPerDelivery) {
            long wait = waitFor(delivery);
            audit.lockedOut("delivery", deliveryId.toString(), (int) deliveryCount, maxPerDelivery, wait);
            throw new RateLimitExceededException(
                    "Too many incorrect handover codes for this delivery. Try again in "
                            + wait + " seconds.", wait);
        }

        if (courier != null && courierCount >= maxPerCourier) {
            long wait = waitFor(courier);
            audit.lockedOut("courier", courierId.toString(), (int) courierCount, maxPerCourier, wait);
            throw new RateLimitExceededException(
                    "Too many incorrect handover codes. Try again in " + wait + " seconds.", wait);
        }
    }

    /**
     * Records one wrong code against both the delivery and the courier.
     *
     * <p>A store failure here is logged but not thrown: the caller is already
     * answering "invalid code", and the very next {@link #checkAllowed} refuses
     * while the store is still down, so the lost write is not a way through.
     */
    public void recordFailure(UUID deliveryId, UUID courierId) {
        try {
            store.increment(deliveryKey(deliveryId), window);
            if (courierId != null) {
                store.increment(courierKey(courierId), window);
            }
        } catch (AttemptStoreUnavailableException e) {
            audit.failClosed("record a wrong handover code", deliveryId.toString(), e);
        }
    }

    /**
     * A correct code clears the delivery's failures. The courier's count is left
     * alone and ages out: one order closed legitimately says nothing about the
     * next, but a courier whose failures keep arriving should still meet the
     * looser ceiling.
     */
    public void recordSuccess(UUID deliveryId) {
        try {
            store.clear(deliveryKey(deliveryId));
        } catch (AttemptStoreUnavailableException e) {
            audit.failClosed("clear a handover counter", deliveryId.toString(), e);
        }
    }

    /**
     * Whole seconds the caller must wait. The counter's own remaining TTL is the
     * honest answer; when it cannot be read, the whole window is the safe
     * over-estimate. Clamped so a {@code Retry-After} is never zero and never
     * longer than the window.
     */
    private long waitFor(String key) {
        long ttl;
        try {
            ttl = store.retryAfterSeconds(key);
        } catch (AttemptStoreUnavailableException e) {
            ttl = -1L;
        }
        long max = Math.max(1L, window.toSeconds());
        return ttl < 1L || ttl > max ? max : ttl;
    }

    private static String deliveryKey(UUID deliveryId) {
        return "handover:delivery:" + deliveryId;
    }

    private static String courierKey(UUID courierId) {
        return "handover:courier:" + courierId;
    }
}
