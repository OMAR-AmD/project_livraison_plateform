package com.delivery.backend.modules.field;

import com.delivery.backend.modules.ai.TrajectoryFraudService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FieldTraceStoreTest {

    @Mock private FieldRunRepository runRepository;
    @Mock private FieldTracePointRepository pointRepository;

    private FieldCollectionProperties properties;
    private FieldTraceStore store;

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();
    private final Instant recordedAtInstant = Instant.parse("2026-11-04T10:00:00Z");
    private final TrajectoryFraudService.Verdict verdict =
            new TrajectoryFraudService.Verdict(
                    0.91, 0.82, true, true, true,
                    new double[]{1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0},
                    1.0, 7.0, 300.0);

    @BeforeEach
    void setUp() {
        properties = new FieldCollectionProperties();
        properties.setRetentionDays(90);
        properties.setConsentVersion("field-v1");
        store = new FieldTraceStore(runRepository, pointRepository, properties);
    }

    @Test
    @DisplayName("first fix creates the run and stores the exact eight features")
    void createsRunAndPointOnFirstFix() {
        when(runRepository.findByDeliveryId(deliveryId)).thenReturn(Optional.empty());
        when(runRepository.save(any(FieldRun.class))).thenAnswer(inv -> inv.getArgument(0));

        store.record(deliveryId, courierId, FieldRunSource.FIELD,
                33.57351, -7.58951, verdict, recordedAtInstant, "rf-60t@0.82");

        ArgumentCaptor<FieldRun> runCaptor = ArgumentCaptor.forClass(FieldRun.class);
        verify(runRepository, times(2)).save(runCaptor.capture());
        FieldRun savedRun = runCaptor.getAllValues().get(0);
        assertEquals(deliveryId, savedRun.getDeliveryId());
        assertEquals(courierId, savedRun.getCourierId());
        assertEquals(FieldRunSource.FIELD, savedRun.getSource());
        // The same managed instance is mutated to point_count=1 before the
        // second save, so only the final state is asserted below.
        assertEquals("field-v1", savedRun.getConsentVersion());
        assertEquals(LocalDateTime.ofInstant(recordedAtInstant, ZoneId.systemDefault()).plusDays(90),
                savedRun.getRetentionUntil());
        assertEquals(1, runCaptor.getAllValues().get(1).getPointCount());

        ArgumentCaptor<FieldTracePoint> pointCaptor = ArgumentCaptor.forClass(FieldTracePoint.class);
        verify(pointRepository).save(pointCaptor.capture());
        FieldTracePoint point = pointCaptor.getValue();
        assertEquals(FieldTraceStore.coarsen(33.57351), point.getLatitude(), 1e-12);
        assertEquals(FieldTraceStore.coarsen(-7.58951), point.getLongitude(), 1e-12);
        assertEquals(1.0, point.getSpeedMps(), 1e-12);
        assertEquals(8.0, point.getProgressRate(), 1e-12);
        assertEquals(0.91, point.getScore(), 1e-12);
        assertEquals(0.82, point.getThreshold(), 1e-12);
        assertTrue(point.isFraud());
        assertTrue(point.isRunFlagged());
        assertEquals("rf-60t@0.82", point.getModelVersion());
    }

    @Test
    @DisplayName("an existing run is reused and its point count increments")
    void reusesExistingRun() {
        FieldRun run = new FieldRun(deliveryId, courierId, FieldRunSource.FIELD,
                LocalDateTime.ofInstant(recordedAtInstant, ZoneId.systemDefault()),
                "field-v1", 90);
        when(runRepository.findByDeliveryId(deliveryId)).thenReturn(Optional.of(run));

        store.record(deliveryId, courierId, FieldRunSource.FIELD,
                33.5, -7.5, verdict, recordedAtInstant, "rf-60t@0.82");

        assertEquals(1, run.getPointCount());
        verify(pointRepository).save(any(FieldTracePoint.class));
        verify(runRepository).save(run);
    }

    @Test
    @DisplayName("a run never mixes FIELD and SIMULATED fixes")
    void rejectsSourceMismatch() {
        FieldRun run = new FieldRun(deliveryId, courierId, FieldRunSource.SIMULATED,
                LocalDateTime.ofInstant(recordedAtInstant, ZoneId.systemDefault()),
                "field-v1", 90);
        when(runRepository.findByDeliveryId(deliveryId)).thenReturn(Optional.of(run));

        store.record(deliveryId, courierId, FieldRunSource.FIELD,
                33.5, -7.5, verdict, recordedAtInstant, "rf-60t@0.82");

        assertEquals(0, run.getPointCount());
        verifyNoInteractions(pointRepository);
        verify(runRepository, never()).save(any(FieldRun.class));
    }

    @Test
    @DisplayName("coordinates are coarsened to a ~50 m grid")
    void coarsensCoordinates() {
        assertEquals(33.5735, FieldTraceStore.coarsen(33.57351), 1e-9);
        assertEquals(-7.5895, FieldTraceStore.coarsen(-7.58951), 1e-9);
    }
}
