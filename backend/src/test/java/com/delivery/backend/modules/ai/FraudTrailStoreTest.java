package com.delivery.backend.modules.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the trail store.
 *
 * <p>The store is backed by a hand-rolled in-memory stand-in for Redis rather
 * than an embedded server. That is deliberate on one specific point: the real
 * Redis {@code LPUSH} prepends, so the store reads newest-first out of Redis and
 * must reverse before anyone draws it. An embedded Redis would catch that bug.
 * A hand-written list that appends would not, which is why this one prepends.
 */
class FraudTrailStoreTest {

    /** Stands in for Redis. LPUSH prepends, exactly as Redis does. */
    private final Map<String, LinkedList<String>> db = new ConcurrentHashMap<>();
    private FraudTrailStore store;
    private RuntimeException redisDown;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        db.clear();
        redisDown = null;

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);

        when(lists.leftPush(anyString(), anyString())).thenAnswer(inv -> {
            if (redisDown != null) {
                throw redisDown;
            }
            String key = inv.getArgument(0);
            db.computeIfAbsent(key, k -> new LinkedList<>()).addFirst(inv.getArgument(1));
            return db.get(key).size();
        });
        // trim() is void in Spring Data Redis 3.x, so it is stubbed with
        // doNothing rather than thenReturn.
        doNothing().when(lists).trim(anyString(), anyLong(), anyLong());
        when(lists.range(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            if (redisDown != null) {
                throw redisDown;
            }
            LinkedList<String> v = db.get(inv.getArgument(0));
            return v == null ? List.of() : new ArrayList<>(v);
        });
        when(redis.opsForList()).thenReturn(lists);
        when(redis.keys(anyString())).thenAnswer(inv -> {
            String pattern = inv.getArgument(0);
            if (redisDown != null) {
                throw redisDown;
            }
            // Redis expands the glob; this double has to as well, otherwise the
            // stub silently matches nothing and every listing looks empty.
            // The return type must be a Set, not a List: RedisTemplate.keys
            // declares Set<String>, and Mockito rejects a mismatched answer --
            // which allTrails() then swallows as an empty result.
            String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
            return new java.util.LinkedHashSet<>(db.keySet().stream()
                    .filter(k -> k.startsWith(prefix)).collect(java.util.stream.Collectors.toList()));
        });
        when(redis.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);

        store = new FraudTrailStore(redis);
    }

    private static TrajectoryFraudService.Verdict verdict(double score, boolean fraud, double threshold) {
        return new TrajectoryFraudService.Verdict(score, threshold, fraud, false,
                new double[8], 3.0, 0.0, 1200.0);
    }

    @Test
    @DisplayName("a trail is read oldest-first, because LPUSH leaves Redis newest-first")
    void aTrailIsReadOldestFirst() {
        UUID id = UUID.randomUUID();
        store.record(id, verdict(0.10, false, 0.82));
        store.record(id, verdict(0.20, false, 0.82));
        store.record(id, verdict(0.95, true, 0.82));

        List<FraudTrailStore.Point> points = store.trail(id).points();

        assertEquals(3, points.size(), "all three fixes kept");
        assertEquals(0.10, points.get(0).score(), 1e-9, "oldest point first");
        assertEquals(0.20, points.get(1).score(), 1e-9);
        assertEquals(0.95, points.get(2).score(), 1e-9, "newest point last");
        assertTrue(points.get(2).fraud(), "the flagged one is the newest");
        assertFalse(points.get(0).fraud(), "the honest ones are not");
    }

    @Test
    @DisplayName("the summary separates an isolated spike from sustained behaviour")
    void theSummarySeparatesASpikeFromSustainedBehaviour() {
        UUID spike = UUID.randomUUID();
        // The flagged fix is in the middle on purpose. Peak and last are only
        // distinct when the trajectory recovers afterwards, which is exactly
        // the case where showing only "last" would quietly hide the alert.
        store.record(spike, verdict(0.10, false, 0.82));
        store.record(spike, verdict(0.99, true, 0.82));
        store.record(spike, verdict(0.10, false, 0.82));

        FraudTrailStore.Summary s = store.trail(spike).summary();

        assertTrue(s.flagged(), "one flagged fix is enough to badge it");
        assertEquals(1, s.flaggedFixes());
        assertEquals(3, s.totalFixes());
        assertEquals(33, s.flaggedPercent(), "1 of 3 reads as a spike, not a pattern");
        assertEquals(0.99, s.peakScore(), 1e-9, "peak survives a later clean fix");
        assertEquals(0.10, s.lastScore(), 1e-9, "last is kept separately from peak");
    }

    @Test
    @DisplayName("each point carries the threshold its own decision used")
    void eachPointCarriesTheThresholdItsDecisionUsed() {
        UUID id = UUID.randomUUID();
        // The model is redeployed with a stricter threshold between two fixes.
        // A graph drawn against the current threshold would relabel history.
        store.record(id, verdict(0.85, true, 0.82));
        store.record(id, verdict(0.85, false, 0.90));

        List<FraudTrailStore.Point> points = store.trail(id).points();

        assertEquals(0.82, points.get(0).threshold(), 1e-9, "first fix decided against 0.82");
        assertTrue(points.get(0).fraud(), "0.85 clears 0.82");
        assertEquals(0.90, points.get(1).threshold(), 1e-9, "second fix decided against 0.90");
        assertFalse(points.get(1).fraud(), "0.85 does not clear 0.90, so the same score flipped");
    }

    @Test
    @DisplayName("an unreadable point does not take the whole curve down")
    void anUnreadablePointDoesNotTakeTheWholeCurveDown() {
        UUID id = UUID.randomUUID();
        store.record(id, verdict(0.10, false, 0.82));
        db.get("fraude:score:" + id).addFirst("{not json at all");
        store.record(id, verdict(0.95, true, 0.82));

        FraudTrailStore.Trail t = store.trail(id);

        assertNotNull(t, "one corrupt point must not lose the trail");
        assertEquals(2, t.totalFixes(), "the two readable points survive");
        assertEquals(0.95, t.peakScore(), 1e-9);
    }

    @Test
    @DisplayName("record never throws, because a lost trail must not cost a broadcast")
    void recordNeverThrows() {
        redisDown = new RuntimeException("OOM command not allowed");

        assertDoesNotThrow(() -> store.record(UUID.randomUUID(), verdict(0.99, true, 0.82)),
                "Redis being down must not propagate into the position update");
    }

    @Test
    @DisplayName("reading a trail of a delivery that was never scored returns nothing")
    void readingAnUnscoredDeliveryReturnsNothing() {
        assertNull(store.trail(UUID.randomUUID()), "no trail is better than an empty curve that looks measured");
        assertEquals(0, store.allTrails().size());
    }

    @Test
    @DisplayName("allTrails ignores keys that are not trails instead of dying on them")
    void allTrailsIgnoresForeignKeys() {
        store.record(UUID.randomUUID(), verdict(0.99, true, 0.82));
        db.put("fraude:score:not-a-uuid", new LinkedList<>(List.of("{}")));
        db.put("fraude:score:", new LinkedList<>(List.of("{}")));

        List<FraudTrailStore.Trail> trails = store.allTrails();

        assertEquals(1, trails.size(), "one real trail, the two junk keys did not throw");
    }

    @Test
    @DisplayName("listing trails raises when Redis is unreachable, so the panel can say so")
    void listingTrailsRaisesWhenRedisIsUnreachable() {
        store.record(UUID.randomUUID(), verdict(0.99, true, 0.82));
        redisDown = new RuntimeException("connection refused");

        // Deliberately NOT an empty list. An admin panel that renders "no
        // anomalies" when it simply could not reach Redis is worse than one
        // that renders red: the first claims the fleet is clean when nothing
        // was checked. The dashboard has to be able to tell blind from clean.
        assertThrows(RuntimeException.class, () -> store.allTrails(),
                "an unreachable Redis must surface, not masquerade as a clean fleet");
    }
}