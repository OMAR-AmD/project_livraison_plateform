package com.delivery.backend.modules.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run-level alert rule, stated once and pinned at its boundaries.
 *
 * <p>Both the live alert and the dashboard badge call into this class, so this
 * is the one place the policy can be checked without a model, a clock, or Redis.
 * That matters: the rule was tuned offline ({@code ml/tune_run_rule.py},
 * {@code ml/measure_or_clause.py}) and shipping it means the Java has to agree
 * with the measured rule exactly, clause for clause.
 *
 * <p>The boundaries are the point. {@code >=} instead of {@code >}, and
 * "consecutive" versus "total", are the two mutations this file is built to
 * catch.
 */
class RunRuleTest {

    private static final double JUST_BELOW_HIGH = 0.9699;
    private static final double AT_HIGH = 0.97;

    // ------------------------------------------------------- streaming clause

    @Test
    @DisplayName("two flags in a row are not yet a run")
    void twoInARowIsNotARun() {
        assertFalse(RunRule.flagged(2, 0.85), "the rule needs k=3 consecutive flags");
    }

    @Test
    @DisplayName("three flags in a row trip the rule")
    void threeInARowTripsTheRule() {
        assertTrue(RunRule.flagged(3, 0.85));
    }

    @Test
    @DisplayName("a single fix at the high-confidence bound trips the rule")
    void theHighConfidenceBoundIsInclusive() {
        // Exactly 0.97 must count. The offline rule uses `s >= t`, and a strict
        // `>` here would silently drop the boundary case the threshold was set on.
        assertTrue(RunRule.flagged(1, AT_HIGH));
        assertFalse(RunRule.flagged(1, JUST_BELOW_HIGH));
    }

    @Test
    @DisplayName("a run counter of zero with a moderate score is not flagged")
    void zeroCounterModerateScoreIsClean() {
        assertFalse(RunRule.flagged(0, 0.5));
    }

    // ------------------------------------------------------------ batch clause

    @Test
    @DisplayName("the batch form finds a run of three in the middle of a trail")
    void theBatchFormFindsARunOfThree() {
        // Sorted oldest -> newest, as FraudTrailStore hands it over.
        assertTrue(RunRule.runFlagged(
                List.of(false, true, true, true, false),
                List.of(0.10, 0.85, 0.85, 0.85, 0.10)));
    }

    @Test
    @DisplayName("the batch form does not count non-consecutive flags")
    void theBatchFormDoesNotCountSpreadFlags() {
        // Three flagged fixes, but never three in a row -- this is exactly the
        // false positive the rule exists to remove.
        assertFalse(RunRule.runFlagged(
                List.of(true, true, false, true),
                List.of(0.85, 0.85, 0.10, 0.85)));
    }

    @Test
    @DisplayName("the batch form honours the single high-confidence fix")
    void theBatchFormHonoursTheHighConfidenceFix() {
        assertTrue(RunRule.runFlagged(
                List.of(false, true, false),
                List.of(0.10, 0.99, 0.10)));
    }

    @Test
    @DisplayName("the batch form treats a break as a reset")
    void theBatchFormResetsOnABreak() {
        assertFalse(RunRule.runFlagged(
                List.of(true, true, false, true, true),
                List.of(0.85, 0.85, 0.10, 0.85, 0.85)));
    }

    @Test
    @DisplayName("an empty trail is not flagged")
    void anEmptyTrailIsNotFlagged() {
        assertFalse(RunRule.runFlagged(List.of(), List.of()));
    }
}
