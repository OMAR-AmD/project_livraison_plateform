package com.delivery.backend.modules.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fraud detector has to actually flag the fraud, in the live code path, not
 * just score correctly in isolation.
 *
 * <p>These tests drive {@link TrajectoryFraudService#evaluate} with the same
 * motion patterns the Python simulator generates, and assert on the decision a
 * dispatcher would see. The coordinates are Casablanca, matching the training
 * scenario, so the model is being asked the question it was trained for rather
 * than extrapolating.
 *
 * <p>The anti-vacuity rule here: each test asserts BOTH that the honest run is
 * clean and that the fraudulent one is flagged. A test that only checks "honest
 * run is fine" passes on a detector that always returns 0. A test that only
 * checks "fraud is caught" passes on one that flags everything. Both halves have
 * to hold at once.
 *
 * <p><b>On time.</b> "Time without progress" is a feature, so these tests cannot
 * run at wall-clock speed: 40 fixes executed in five milliseconds measure five
 * milliseconds of stall and prove nothing. {@link #evaluateAt} therefore takes
 * the fix timestamp explicitly, and the simulator advances a virtual clock. The
 * production entry point {@link #evaluate} delegates to it using
 * {@code Instant.now()}, so the tested path is the shipped path.
 */
class TrajectoryFraudServiceTest {

    private static final double TARGET_LAT = 33.5891;
    private static final double TARGET_LNG = -7.6311;

    private TrajectoryFraudService service;
    private UUID deliveryId;

    /** Virtual clock, advanced by {@link #tick()} instead of by sleeping. */
    private Instant now;

    @BeforeEach
    void setUp() {
        service = new TrajectoryFraudService(TrajectoryFraudModel.load());
        deliveryId = UUID.randomUUID();
        now = Instant.parse("2026-09-30T10:00:00Z");
    }

    /** Advance the virtual clock by one production fix interval (10 s). */
    private void tick() {
        now = now.plusSeconds((long) TrajectoryFraudService.NOMINAL_INTERVAL_S);
    }

    private TrajectoryFraudService.Verdict evaluateAt(double lat, double lng) {
        return service.evaluateAt(deliveryId, lat, lng, TARGET_LAT, TARGET_LNG, now);
    }

    /** Metres north / east, converted to degrees at the target latitude. */
    private static double northMetres(double m) {
        return m / 111_320.0;
    }

    private static double eastMetres(double m) {
        return m / (111_320.0 * Math.cos(Math.toRadians(TARGET_LAT)));
    }

    /**
     * An honest courier driving the last 3 km to the drop-off. Starts 3 km south,
     * closes steadily, arrives.
     *
     * @return the last verdict
     */
    private TrajectoryFraudService.Verdict driveNormally(int fixes) {
        TrajectoryFraudService.Verdict last = null;
        for (int i = 0; i < fixes; i++) {
            double remaining = 3000.0 - i * (3000.0 / fixes);
            last = evaluateAt(TARGET_LAT - northMetres(remaining), TARGET_LNG);
            tick();
        }
        return last;
    }

    /**
     * The subtle fraud, as the model actually learned it: a plausible approach
     * that stalls short of the drop-off, then completes from across the city.
     *
     * <p>An earlier version of this test asserted the STALL was flagged on its
     * own. It failed, and the failure was right. 22% of honest simulated fixes
     * have more than 90 s without progress -- a courier circling a block or
     * waiting at a light is genuinely stationary and genuinely far from the
     * destination -- so "stationary and far" is not evidence of anything, and the
     * model is right not to act on it. What it does not survive is the jump.
     */
    private TrajectoryFraudService.Verdict driveThenStallThenVanish(int approachFixes,
                                                                     int stallFixes) {
        TrajectoryFraudService.Verdict last = null;
        for (int i = 0; i < approachFixes; i++) {
            double remaining = 4000.0 - i * (2000.0 / approachFixes);
            last = evaluateAt(TARGET_LAT - northMetres(remaining), TARGET_LNG);
            tick();
        }
        for (int i = 0; i < stallFixes; i++) {
            last = evaluateAt(TARGET_LAT - northMetres(2000.0) + northMetres(i * 0.5),
                    TARGET_LNG + eastMetres(i * 0.4));
            tick();
        }
        // The claim of arrival, made from somewhere else entirely.
        last = evaluateAt(TARGET_LAT + northMetres(900.0), TARGET_LNG + eastMetres(700.0));
        return last;
    }

    /** Just the stall, with no jump afterwards. */
    private TrajectoryFraudService.Verdict driveThenStall(int approachFixes, int stallFixes) {
        TrajectoryFraudService.Verdict last = null;
        for (int i = 0; i < approachFixes; i++) {
            double remaining = 4000.0 - i * (2000.0 / approachFixes);
            last = evaluateAt(TARGET_LAT - northMetres(remaining), TARGET_LNG);
            tick();
        }
        // Parked 2 km short, barely drifting.
        for (int i = 0; i < stallFixes; i++) {
            last = evaluateAt(TARGET_LAT - northMetres(2000.0) + northMetres(i * 0.5),
                    TARGET_LNG + eastMetres(i * 0.4));
            tick();
        }
        return last;
    }

    /**
     * A courier who marks a position from across the city: one enormous jump.
     */
    private TrajectoryFraudService.Verdict teleport() {
        evaluateAt(TARGET_LAT - northMetres(3000.0), TARGET_LNG);
        tick();
        return evaluateAt(TARGET_LAT + northMetres(1500.0), TARGET_LNG + eastMetres(1200.0));
    }

    @Test
    @DisplayName("an honest approach to the drop-off is not flagged")
    void honestApproachIsClean() {
        TrajectoryFraudService.Verdict last = driveNormally(20);
        assertFalse(last.fraud(),
                "an honest courier driving to the drop-off was flagged with score "
                        + last.score() + " (features: speed=" + last.speedMps()
                        + " m/s, stall=" + last.stallSeconds() + " s, dist="
                        + last.distanceToTargetM() + " m)");
    }

    @Test
    @DisplayName("stalling short of the drop-off is NOT flagged on its own")
    void stallAloneIsNotEvidence() {
        // Pinned deliberately. An earlier version of this test asserted the
        // opposite and failed, which is how the following was learned: 22% of
        // honest simulated fixes exceed 90 s without progress, because circling a
        // block and waiting at a light look exactly like this. Treating a stall
        // as fraud would have produced an alert on a fifth of honest deliveries
        // and trained the dispatcher to dismiss the badge.
        TrajectoryFraudService.Verdict last = driveThenStall(12, 40);
        assertFalse(last.fraud(),
                "a stationary courier must not be flagged just for being stationary, "
                        + "even after " + last.stallSeconds() + " s (score " + last.score() + ")");
    }

    @Test
    @DisplayName("stalling and then completing from across the city IS flagged")
    void stallThenVanishIsFlagged() {
        TrajectoryFraudService.Verdict last = driveThenStallThenVanish(12, 40);
        assertTrue(last.fraud(),
                "claiming arrival from across the city must be flagged (score "
                        + last.score() + ", threshold " + last.threshold() + ")");
    }

    @Test
    @DisplayName("a position reported from across the city is flagged")
    void teleportIsFlagged() {
        TrajectoryFraudService.Verdict last = teleport();
        assertTrue(last.fraud(),
                "a 3.5 km jump between fixes should be flagged, score was " + last.score());
    }

    @Test
    @DisplayName("the same run is judged differently because of what happened, not because of the coords")
    void decisionDependsOnTheMotionNotThePlace() {
        // Identical coordinate, opposite verdicts: one is the first fix of a run,
        // the other is reached after stalling. If these matched, the model would
        // be reacting to the location rather than to the behaviour.
        TrajectoryFraudService other = new TrajectoryFraudService(TrajectoryFraudModel.load());
        TrajectoryFraudService.Verdict fresh = other.evaluateAt(deliveryId,
                TARGET_LAT - northMetres(2000.0), TARGET_LNG, TARGET_LAT, TARGET_LNG, now);

        TrajectoryFraudService.Verdict vanished = driveThenStallThenVanish(12, 40);

        assertTrue(vanished.score() > fresh.score(),
                "the run that vanished (" + vanished.score() + ") must outscore the same "
                        + "position as a fresh fix (" + fresh.score() + ")");
    }

    @Test
    @DisplayName("a repeated alert is suppressed by the cooldown, but a clean run resets it")
    void alertsDoNotSpam() {
        // A 40-fix stall would otherwise raise 40 notifications.
        driveThenStallThenVanish(12, 40);

        int alerts = 0;
        for (int i = 0; i < 10; i++) {
            TrajectoryFraudService.Verdict v = evaluateAt(
                    TARGET_LAT - northMetres(2000.0) + northMetres(i * 0.5), TARGET_LNG);
            if (v.shouldNotify()) {
                alerts++;
            }
            tick();
        }
        assertTrue(alerts == 0,
                "a continuing stall must not re-alert (" + alerts + " extra notifications); "
                        + "a console that fires on every fix gets ignored");

        // Clean driving clears the cooldown, so the next real problem is heard.
        TrajectoryFraudService.Verdict clean = driveNormally(15);
        assertFalse(clean.fraud(), "the recovery run should be clean again");
    }

    @Test
    @DisplayName("forget() clears the run history so the next delivery starts clean")
    void forgetResetsState() {
        driveThenStall(12, 20);
        service.forget(deliveryId);

        TrajectoryFraudService.Verdict fresh = evaluateAt(
                TARGET_LAT - northMetres(2000.0), TARGET_LNG);

        // A first fix can never carry a stall: there is no history yet. If it
        // does, the state leaked across deliveries.
        assertTrue(fresh.stallSeconds() == 0.0,
                "after forget() the stall timer must restart, was " + fresh.stallSeconds());
    }
}