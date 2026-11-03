package com.delivery.backend.modules.field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FieldRetentionJobTest {

    @Mock private FieldRunRepository runRepository;
    @Mock private FieldTracePointRepository pointRepository;

    @Test
    @DisplayName("expired runs: points are deleted before the run")
    void purgesPointsThenRuns() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findIdsByRetentionUntilBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(runId));
        when(runRepository.deleteByRetentionUntilBefore(any(LocalDateTime.class))).thenReturn(1);

        new FieldRetentionJob(runRepository, pointRepository).purgeExpired();

        verify(pointRepository).deleteByRunIdIn(List.of(runId));
        verify(runRepository).deleteByRetentionUntilBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("nothing expired: no delete")
    void noExpiredRunsDoesNothing() {
        when(runRepository.findIdsByRetentionUntilBefore(any(LocalDateTime.class)))
                .thenReturn(List.of());

        new FieldRetentionJob(runRepository, pointRepository).purgeExpired();

        verifyNoInteractions(pointRepository);
        verify(runRepository, never()).deleteByRetentionUntilBefore(any(LocalDateTime.class));
    }
}
