package com.delivery.backend.modules.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Java evaluator to scikit-learn.
 *
 * <p>The claim being defended is "the backend scores exactly what Python trained".
 * That is a cross-language numerical claim, so it is checked against Python's own
 * {@code predict_proba} output for 200 vectors rather than against a value I
 * computed by hand and would have rounded until it agreed.
 */
class TrajectoryFraudModelTest {

    private static final double TOLERANCE = 1e-6;

    private static TrajectoryFraudModel realModel() {
        return TrajectoryFraudModel.load();
    }

    @Test
    @DisplayName("the exported model is on the classpath and is not empty")
    void modelLoads() {
        TrajectoryFraudModel model = realModel();
        assertTrue(model.treeCount() > 0, "model shipped with no trees");
        assertTrue(model.threshold() > 0 && model.threshold() < 1,
                "threshold must be a probability, was " + model.threshold());
    }

    @Test
    @DisplayName("Java reproduces sklearn's predict_proba within 1e-6 on 200 vectors")
    void matchesSklearnExactly() throws Exception {
        TrajectoryFraudModel model = realModel();

        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/trajectory_fraud_rf_parity.json")) {
            if (in == null) {
                // Fixture is a test-only artefact. Without it the parity claim is
                // simply unverified, so fail loudly rather than pass vacuously.
                throw new IllegalStateException(
                        "trajectory_fraud_rf_parity.json missing: run ml/train_fraud_model.py");
            }
            fixture = new ObjectMapper().readTree(in);
        }

        JsonNode vectors = fixture.path("vectors");
        JsonNode scores = fixture.path("scores");
        assertEquals(vectors.size(), scores.size(), "fixture vectors/scores length mismatch");
        assertTrue(vectors.size() >= 100,
                "fixture has only " + vectors.size() + " vectors, too few to be meaningful");

        double worst = 0;
        int worstAt = -1;
        for (int i = 0; i < vectors.size(); i++) {
            JsonNode v = vectors.get(i);
            double[] features = new double[TrajectoryFraudModel.FEATURE_COUNT];
            for (int f = 0; f < features.length; f++) {
                features[f] = v.get(f).asDouble();
            }
            double javaScore = model.score(features);
            double pythonScore = scores.get(i).asDouble();
            double delta = Math.abs(javaScore - pythonScore);
            if (delta > worst) {
                worst = delta;
                worstAt = i;
            }
        }

        assertTrue(worst < TOLERANCE,
                "Java deviates from sklearn by " + worst + " at vector " + worstAt
                        + "; the Java evaluator does not reproduce the trained model");
    }

    @Test
    @DisplayName("a courier parked short of the target scores higher than one driving normally")
    void separatesDriftFromNormalDriving() {
        TrajectoryFraudModel model = realModel();

        // 7 m/s, closing on the target, heading roughly the right way.
        double[] driving = {
                7.0,   // speed_mps
                0.02,  // accel_mps2
                1800.0,// dist_to_target_m
                0.62,  // progress_ratio
                3.0,   // heading_change_deg
                12.0,  // target_bearing_err
                0.0,   // stall_seconds
                1.8,   // progress_rate
        };

        // Same courier, stopped 2 km short, no headway for three minutes.
        double[] parked = {
                0.3,   // speed_mps
                -0.01, // accel_mps2
                2000.0,
                0.62,
                1.0,
                60.0,
                180.0, // stall_seconds
                0.0,   // progress_rate
        };

        assertTrue(model.score(parked) > model.score(driving),
                "the stall case ("
                        + model.score(parked) + ") must outrank normal driving ("
                        + model.score(driving) + ")");
    }

    @Test
    @DisplayName("a wrong-shaped feature vector is rejected rather than scored as normal")
    void rejectsMalformedInput() {
        TrajectoryFraudModel model = realModel();
        assertThrows(IllegalArgumentException.class, () -> model.score(new double[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> model.score(null));
    }

    @Test
    @DisplayName("a model with no trees is rejected at load, not silently scoring 0")
    void refusesDegenerateModel() {
        assertThrows(IllegalStateException.class, () -> TrajectoryFraudModel.parse(
                "{\"format\":\"x\",\"threshold\":0.5,\"trees\":[]}"));
        assertThrows(IllegalStateException.class, () -> TrajectoryFraudModel.parse(
                "{\"format\":\"x\",\"trees\":[]}"));
        assertThrows(IllegalStateException.class, () -> TrajectoryFraudModel.parse("not json"));
    }

    @Test
    @DisplayName("a corrupt split cannot loop forever")
    void malformedTreeIsBounded() {
        // left/right pointing back at node 0 is a cycle; without a guard this
        // spins. It must terminate instead.
        TrajectoryFraudModel model = TrajectoryFraudModel.parse(
                "{\"threshold\":0.5,\"trees\":[{\"left\":0,\"right\":0,\"feature\":0,"
                        + "\"threshold\":0.0,\"value\":0.9,\"isLeaf\":false}]}");
        double score = model.score(new double[TrajectoryFraudModel.FEATURE_COUNT]);
        assertTrue(Double.isFinite(score), "score must be finite, was " + score);
    }
}