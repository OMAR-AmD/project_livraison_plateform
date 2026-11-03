package com.delivery.backend.modules.field;

import com.delivery.backend.modules.ai.TrajectoryFraudModel;
import com.delivery.backend.modules.ai.TrajectoryFraudService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FieldTraceRecorderTest {

    @Mock private FieldTraceStore store;
    @Mock private TrajectoryFraudModel model;

    private FieldCollectionProperties properties;
    private FieldTraceRecorder recorder;

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();
    private final Instant recordedAt = Instant.parse("2026-11-04T10:00:00Z");
    private final TrajectoryFraudService.Verdict verdict =
            new TrajectoryFraudService.Verdict(
                    0.91, 0.82, true, true, true,
                    new double[]{1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0},
                    1.0, 7.0, 300.0);

    @BeforeEach
    void setUp() {
        properties = new FieldCollectionProperties();
        properties.setEnabled(true);
        properties.setRequireConsent(true);
        lenient().when(model.treeCount()).thenReturn(60);
        lenient().when(model.threshold()).thenReturn(0.82);
        recorder = new FieldTraceRecorder(store, properties, model, Runnable::run);
    }

    @Test
    @DisplayName("collection disabled: no write")
    void disabledCollectionDoesNotRecord() {
        properties.setEnabled(false);

        recorder.record(deliveryId, courierId, true, FieldRunSource.FIELD,
                33.5, -7.5, verdict, recordedAt);

        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("no consent: no write, even when collection is enabled")
    void missingConsentDoesNotRecord() {
        recorder.record(deliveryId, courierId, false, FieldRunSource.FIELD,
                33.5, -7.5, verdict, recordedAt);

        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("consented field fix: write with the exact features and model version")
    void recordsConsentedFieldFix() {
        recorder.record(deliveryId, courierId, true, FieldRunSource.FIELD,
                33.5, -7.5, verdict, recordedAt);

        verify(store).record(deliveryId, courierId, FieldRunSource.FIELD,
                33.5, -7.5, verdict, recordedAt, "rf-60t@0.82");
    }

    @Test
    @DisplayName("simulated fixes are stored with their source, never promoted to FIELD")
    void simulatedSourceIsPreserved() {
        recorder.record(deliveryId, courierId, true, FieldRunSource.SIMULATED,
                33.5, -7.5, verdict, recordedAt);

        verify(store).record(deliveryId, courierId, FieldRunSource.SIMULATED,
                33.5, -7.5, verdict, recordedAt, "rf-60t@0.82");
    }

    @Test
    @DisplayName("no verdict (fraud scoring failed): no write")
    void nullVerdictDoesNotRecord() {
        recorder.record(deliveryId, courierId, true, FieldRunSource.FIELD,
                33.5, -7.5, null, recordedAt);

        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("missing coordinates: no write")
    void missingCoordinatesDoesNotRecord() {
        recorder.record(deliveryId, courierId, true, FieldRunSource.FIELD,
                null, -7.5, verdict, recordedAt);

        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a full async queue is swallowed: the GPS broadcast must not fail")
    void queueRejectionIsSwallowed() {
        FieldTraceRecorder rejecting = new FieldTraceRecorder(
                store, properties, model,
                task -> { throw new TaskRejectedException("queue full"); });

        assertDoesNotThrow(() -> rejecting.record(deliveryId, courierId, true,
                FieldRunSource.FIELD, 33.5, -7.5, verdict, recordedAt));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a storage failure is swallowed: a lost trace is cheaper than a lost position")
    void storageFailureIsSwallowed() {
        doThrow(new RuntimeException("database down"))
                .when(store).record(any(), any(), any(), anyDouble(), anyDouble(),
                        any(), any(), anyString());

        assertDoesNotThrow(() -> recorder.record(deliveryId, courierId, true,
                FieldRunSource.FIELD, 33.5, -7.5, verdict, recordedAt));
    }
}
