package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.RateLimitExceededException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handover throttle against a real Redis.
 *
 * <p>The unit tests mock {@code StringRedisTemplate}, which is the right shape
 * for the policy but proves nothing about the two claims that only exist because
 * the counters moved out of the process: that one script really does INCR and
 * EXPIRE atomically, and that two limiter instances really do share one budget. A
 * mock cannot show either -- it is told what to return -- so this test talks to a
 * server.
 *
 * <p>It is <b>skipped, not failed</b>, when nothing answers on the Redis port, so
 * the suite stays green on a machine without one. The defaults match the compose
 * Redis; point {@code REDIS_HOST}/{@code REDIS_PORT} elsewhere to run it against a
 * different one. Keys are namespaced under {@code handover:test:} and cleared
 * afterwards.
 */
class HandoverThrottleRedisIntegrationTest {

    private static final String PREFIX = "handover:test:";
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int DELIVERY_LIMIT = 5;
    private static final int COURIER_LIMIT = 20;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisHandoverAttemptStore store;
    private static boolean connected;

    @BeforeAll
    static void connect() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        try {
            // A harmless read that forces a connection: if Redis is not there,
            // Lettuce raises rather than hanging on first real use.
            redis.getExpire(PREFIX + "probe", TimeUnit.SECONDS);
            connected = true;
        } catch (RuntimeException e) {
            connected = false;
        }
        Assumptions.assumeTrue(connected, "no Redis at " + host + ":" + port + "; integration test skipped");

        store = new RedisHandoverAttemptStore(redis);
    }

    @AfterAll
    static void disconnect() {
        // @AfterAll still runs when @BeforeAll aborts on the assumption above, so
        // every step is guarded: a missing Redis must read as "skipped", never as
        // "errored".
        if (connected && redis != null) {
            try {
                Set<String> leftovers = redis.keys(PREFIX + "*");
                if (leftovers != null && !leftovers.isEmpty()) {
                    redis.delete(leftovers);
                }
            } catch (RuntimeException ignored) {
                // The test keys expire on their own; failing to tidy up is not a
                // test failure.
            }
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("the first write arms the window, so no counter can live forever")
    void theFirstWriteArmsATtl() {
        String key = key();
        store.increment(key, WINDOW);

        long ttl = store.retryAfterSeconds(key);
        assertTrue(ttl > 0 && ttl <= WINDOW.toSeconds(),
                "a counter without a TTL is a lockout that never lifts; TTL was " + ttl);
    }

    @Test
    @DisplayName("increment re-arms the expiry on a counter that already exists with no TTL")
    void aKeyWithoutATtlIsRepaired() {
        String key = key();
        redis.opsForValue().set(key, "3"); // no EXPIRE: TTL is -1, the dangerous state
        assertEquals(-1L, redis.getExpire(key, TimeUnit.SECONDS), "precondition: the key has no TTL");

        store.increment(key, WINDOW);

        assertEquals(4L, store.count(key), "the increment still lands on the existing value");
        assertTrue(store.retryAfterSeconds(key) > 0, "the script must repair the missing TTL");
    }

    @Test
    @DisplayName("the counter vanishes when the window passes")
    void theWindowExpires() throws InterruptedException {
        String key = key();
        store.increment(key, Duration.ofSeconds(1));
        assertEquals(1L, store.count(key));

        Thread.sleep(1500);

        assertEquals(0L, store.count(key), "an expired counter must stop refusing guesses");
    }

    @Test
    @DisplayName("clear removes the counter immediately, so a correct code reopens the delivery")
    void clearRemovesTheCounter() {
        String key = key();
        store.increment(key, WINDOW);
        store.clear(key);

        assertEquals(0L, store.count(key));
    }

    @Test
    @DisplayName("two limiter instances share one counter: the budget does not reset per instance")
    void twoInstancesShareOneCounter() {
        UUID delivery = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        HandoverCodeRateLimiter instanceA = newLimiter();
        HandoverCodeRateLimiter instanceB = newLimiter();

        for (int i = 0; i < DELIVERY_LIMIT; i++) {
            instanceA.recordFailure(delivery, courier);
        }

        // Instance B never saw a single failure, yet it must already know the
        // delivery is spent -- that is the entire reason the counter left the
        // process. On the old in-memory limiter B would happily allow five more.
        assertThrows(RateLimitExceededException.class,
                () -> instanceB.checkAllowed(delivery, courier),
                "a per-instance counter would let a second instance double the budget");
    }

    @Test
    @DisplayName("concurrent failures are all counted: the increment is atomic")
    void concurrentFailuresAreNotLost() throws Exception {
        String key = key();
        int attempts = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return store.increment(key, WINDOW);
                }));
            }
            start.countDown();

            long highest = 0;
            for (Future<Long> result : results) {
                highest = Math.max(highest, result.get(10, TimeUnit.SECONDS));
            }

            // INCR hands each caller a distinct number, so the counts run exactly
            // 1..N. A read-modify-write would repeat a value here and lose a
            // failure -- a free guess.
            assertEquals(attempts, highest, "the counts must run 1..N with no repeats");
            assertEquals(attempts, store.count(key), "a lost increment is a free guess");
        } finally {
            pool.shutdownNow();
        }
    }

    private static HandoverCodeRateLimiter newLimiter() {
        return new HandoverCodeRateLimiter(store, new HandoverLockoutAudit(),
                DELIVERY_LIMIT, COURIER_LIMIT, WINDOW);
    }

    private static String key() {
        return PREFIX + UUID.randomUUID();
    }
}
