package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>State is in memory, on purpose.</b> Same trade-off as
 * {@link com.delivery.backend.modules.auth.LoginRateLimiter}: a Redis-backed
 * limiter stops working the moment Redis does, which is the wrong failure
 * direction for a security control. The cost is that counters reset on restart
 * and are per instance; moving to a shared store is a deliberate step for when
 * the platform runs more than one.
 */
@Component
public class HandoverCodeRateLimiter {

    /** Windows are swept this often (in writes) so the map cannot grow without bound. */
    private static final int SWEEP_EVERY = 1024;

    private final int maxPerDelivery;
    private final int maxPerCourier;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final AtomicLong writes = new AtomicLong();

    /**
     * The constructor Spring uses. Annotated because there is a second,
     * package-private constructor for tests: with more than one candidate Spring
     * cannot choose, and the application refuses to start.
     */
    @Autowired
    public HandoverCodeRateLimiter(
            @Value("${application.security.handover-rate-limit.max-per-delivery:5}") int maxPerDelivery,
            @Value("${application.security.handover-rate-limit.max-per-courier:20}") int maxPerCourier,
            @Value("${application.security.handover-rate-limit.window-seconds:900}") long windowSeconds) {
        this(maxPerDelivery, maxPerCourier, Duration.ofSeconds(windowSeconds), Clock.systemUTC());
    }

    HandoverCodeRateLimiter(int maxPerDelivery, int maxPerCourier, Duration window, Clock clock) {
        this.maxPerDelivery = maxPerDelivery;
        this.maxPerCourier = maxPerCourier;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Refuses the attempt if either counter is already exhausted. Called before
     * the code is compared, so a blocked guesser costs no work and gets no
     * signal about whether the code was close.
     *
     * @throws RateLimitExceededException when the caller must wait
     */
    public void checkAllowed(UUID deliveryId, UUID courierId) {
        long now = clock.millis();

        String delivery = deliveryKey(deliveryId);
        if (count(delivery, now) >= maxPerDelivery) {
            long wait = retryAfterSeconds(delivery, now);
            throw new RateLimitExceededException(
                    "Too many incorrect handover codes for this delivery. Try again in "
                            + wait + " seconds.", wait);
        }

        if (courierId != null) {
            String courier = courierKey(courierId);
            if (count(courier, now) >= maxPerCourier) {
                long wait = retryAfterSeconds(courier, now);
                throw new RateLimitExceededException(
                        "Too many incorrect handover codes. Try again in " + wait + " seconds.", wait);
            }
        }
    }

    /** Records one wrong code against both the delivery and the courier. */
    public void recordFailure(UUID deliveryId, UUID courierId) {
        long now = clock.millis();
        increment(deliveryKey(deliveryId), now);
        if (courierId != null) {
            increment(courierKey(courierId), now);
        }
        if (writes.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep(now);
        }
    }

    /**
     * A correct code clears the delivery's failures. The courier's count is left
     * alone and ages out: one order closed legitimately says nothing about the
     * next, but a courier whose failures keep arriving should still meet the
     * looser ceiling.
     */
    public void recordSuccess(UUID deliveryId) {
        failures.remove(deliveryKey(deliveryId));
    }

    private void increment(String key, long now) {
        failures.compute(key, (k, existing) -> {
            if (existing == null || expired(existing, now)) {
                return new Window(now, 1);
            }
            return new Window(existing.startMillis(), existing.count() + 1);
        });
    }

    private int count(String key, long now) {
        Window window = failures.get(key);
        if (window == null || expired(window, now)) {
            return 0;
        }
        return window.count();
    }

    private boolean expired(Window window, long now) {
        return now - window.startMillis() >= this.window.toMillis();
    }

    private long retryAfterSeconds(String key, long now) {
        Window window = failures.get(key);
        if (window == null) {
            return Math.max(1L, this.window.toSeconds());
        }
        long remainingMillis = window.startMillis() + this.window.toMillis() - now;
        return Math.max(1L, (remainingMillis + 999) / 1000);
    }

    private void sweep(long now) {
        failures.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    private static String deliveryKey(UUID deliveryId) {
        return "delivery:" + deliveryId;
    }

    private static String courierKey(UUID courierId) {
        return "courier:" + courierId;
    }

    private record Window(long startMillis, int count) {}
}
