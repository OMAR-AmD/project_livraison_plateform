package com.delivery.backend.modules.field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Purge quotidienne des traces arrivées à échéance.
 *
 * <p>La rétention est une exigence du ticket #5 : une trace terrain n'est pas
 * conservée indéfiniment. La purge supprime d'abord les points, puis le run,
 * pour ne pas laisser de lignes orphelines.
 */
@Component
public class FieldRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(FieldRetentionJob.class);

    private final FieldRunRepository runRepository;
    private final FieldTracePointRepository pointRepository;

    public FieldRetentionJob(FieldRunRepository runRepository,
                             FieldTracePointRepository pointRepository) {
        this.runRepository = runRepository;
        this.pointRepository = pointRepository;
    }

    @Scheduled(cron = "${application.field-collection.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now();
        List<UUID> expired = runRepository.findIdsByRetentionUntilBefore(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        pointRepository.deleteByRunIdIn(expired);
        int runs = runRepository.deleteByRetentionUntilBefore(cutoff);
        log.info("field collection retention: purged {} runs and their trace points", runs);
    }
}
