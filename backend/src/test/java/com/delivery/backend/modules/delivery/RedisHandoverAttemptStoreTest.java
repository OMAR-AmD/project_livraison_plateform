package com.delivery.backend.modules.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Redis-backed counter store: the piece that makes the throttle hold across
 * instances, and the piece that has to fail closed when Redis does not answer.
 *
 * <p>A mocked template is enough here: what matters is not that Redis works, but
 * that the store sends INCR and EXPIRE as one script, that it reads the count it
 * wrote, and that a connection failure surfaces as
 * {@link AttemptStoreUnavailableException} rather than as a silent zero.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisHandoverAttemptStoreTest {

    private static final String KEY = "handover:delivery:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisHandoverAttemptStore store = new RedisHandoverAttemptStore(redis);

    @Test
    @DisplayName("increment returns the count the script reported")
    void incrementReturnsTheScriptCount() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(7L);

        assertEquals(7L, store.increment(KEY, WINDOW));
    }

    @Test
    @DisplayName("one script both INCRs and arms the EXPIRE, keyed by the caller")
    void incrementIsAtomicIncrementAndExpire() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        store.increment(KEY, WINDOW);

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any());

        String body = script.getValue().getScriptAsString();
        assertTrue(body.contains("INCR"), "the counter must actually increment: " + body);
        assertTrue(body.contains("EXPIRE"),
                "the window must be armed in the same call, or a key can outlive it: " + body);
        assertTrue(body.contains("TTL"),
                "the expiry must be re-armed even for a key that already exists without one: " + body);
        assertEquals(List.of(KEY), keys.getValue(), "the counter is keyed by the caller's key");
    }

    @Test
    @DisplayName("count reads the stored number, and treats an absent key as zero")
    void countReadsTheStoredValue() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        when(ops.get(KEY)).thenReturn("4");
        assertEquals(4L, store.count(KEY));

        when(ops.get(KEY)).thenReturn(null);
        assertEquals(0L, store.count(KEY), "no key yet means no failures yet");
    }

    @Test
    @DisplayName("retryAfterSeconds uses the key's own TTL, and -1 when there is no key")
    void retryAfterUsesTheTtl() {
        when(redis.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(600L);
        assertEquals(600L, store.retryAfterSeconds(KEY));

        // Redis answers 0 for a key whose TTL lapsed between two calls. That is
        // not a wait the caller should be told to honour, so it reads as "none".
        when(redis.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(0L);
        assertEquals(-1L, store.retryAfterSeconds(KEY), "a zero TTL is not a wait to report");

        when(redis.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(-2L);
        assertEquals(-1L, store.retryAfterSeconds(KEY), "nothing stored means nothing to wait for");
    }

    @Test
    @DisplayName("clear deletes the key so a correct code reopens the delivery")
    void clearDeletesTheKey() {
        store.clear(KEY);
        verify(redis).delete(KEY);
    }

    @Test
    @DisplayName("a Redis outage becomes AttemptStoreUnavailable, never a silent zero")
    void outageRaisesUnavailable() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThrows(AttemptStoreUnavailableException.class, () -> store.count(KEY),
                "an unreachable store must not be mistaken for 'no failures yet'");
    }

    @Test
    @DisplayName("a non-numeric value is also unavailable, not zero")
    void corruptValueRaisesUnavailable() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(KEY)).thenReturn("not-a-number");

        assertThrows(AttemptStoreUnavailableException.class, () -> store.count(KEY),
                "a value we cannot trust must fail closed, not default to no failures");
    }
}
