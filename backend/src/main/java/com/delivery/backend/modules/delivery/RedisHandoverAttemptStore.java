package com.delivery.backend.modules.delivery;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The shared {@link HandoverAttemptStore}: counters live in Redis, so two backend
 * instances give a guesser one budget instead of one each.
 *
 * <p><b>INCR and EXPIRE together, in one script.</b> Sent as two commands, a
 * dropped connection between them leaves a key with no expiry -- and a counter
 * with no expiry is a lockout that never lifts, precisely the bug a security
 * control must not have. The Lua script does both in a single atomic step, and it
 * re-arms the expiry whenever it finds a counter that already exists without one.
 * Guarding on {@code TTL < 0} rather than on {@code count == 1} costs one extra
 * call per increment and buys two things: the normal first-hit case is covered,
 * and so is a key that lost its TTL some other way -- a value an operator set by
 * hand, or one left behind by an earlier release. The window is never extended
 * for a healthy key, because a live counter's TTL is positive and the guard is
 * skipped.
 *
 * <p><b>Failure is a refusal, not a bypass.</b> Every Redis error is rethrown as
 * {@link AttemptStoreUnavailableException} rather than swallowed. The limiter
 * then refuses the attempt -- and says so in the audit log -- instead of letting
 * the guesser through unthrottled, which is the wrong direction for this control.
 * That is the deliberate trade the old in-memory comment described from the other
 * side: if the cache is where the counter lives, losing the cache has to fail
 * closed.
 *
 * <p>Injects the concrete {@code StringRedisTemplate} rather than
 * {@code RedisTemplate<String, String>} because the project defines its own
 * {@code redisTemplate} bean and Spring autoconfigures {@code stringRedisTemplate}
 * beside it; the generic type leaves two candidates and silently picks by
 * parameter name. {@code FraudTrailStore} documents the same trap.
 */
@Component
public class RedisHandoverAttemptStore implements HandoverAttemptStore {

    /**
     * INCR, then EXPIRE whenever the key has no live TTL, returning the new count.
     * The window is passed in seconds so the counter's life is decided by the
     * policy, not by this class. {@code TTL} answers -1 for a key that exists with
     * no expiry and -2 for one that does not exist; after the INCR the key always
     * exists, so {@code TTL < 0} catches exactly the "no expiry" case.
     */
    private static final RedisScript<Long> INCREMENT_AND_ARM = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return count",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisHandoverAttemptStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration window) {
        try {
            Long count = redis.execute(INCREMENT_AND_ARM, List.of(key), Long.toString(window.toSeconds()));
            return count == null ? 0L : count;
        } catch (DataAccessException e) {
            throw new AttemptStoreUnavailableException("could not count a wrong handover code (" + key + ")", e);
        }
    }

    @Override
    public long count(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException e) {
            throw new AttemptStoreUnavailableException("could not read the handover counter (" + key + ")", e);
        }
    }

    @Override
    public long retryAfterSeconds(String key) {
        try {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            return ttl == null || ttl <= 0L ? -1L : ttl;
        } catch (DataAccessException e) {
            throw new AttemptStoreUnavailableException("could not read the handover counter TTL (" + key + ")", e);
        }
    }

    @Override
    public void clear(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException e) {
            throw new AttemptStoreUnavailableException("could not clear the handover counter (" + key + ")", e);
        }
    }
}
