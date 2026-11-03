package com.delivery.backend.modules.field;

import com.delivery.backend.modules.ai.TrajectoryFraudModel;
import com.delivery.backend.modules.ai.TrajectoryFraudService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Point d'entrée de la collecte passive (#5a).
 *
 * <p>Le recorder filtre les cas qui ne doivent pas produire de données
 * terrain (collecte désactivée, coursier non consentant, verdict absent), puis
 * délègue l'écriture à {@link FieldTraceStore}. Il ne lève jamais : une trace
 * perdue ne doit pas faire échouer un broadcast GPS.
 */
@Service
public class FieldTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(FieldTraceRecorder.class);

    private final FieldTraceStore store;
    private final FieldCollectionProperties properties;
    private final TrajectoryFraudModel model;
    private final Executor executor;

    public FieldTraceRecorder(FieldTraceStore store,
                              FieldCollectionProperties properties,
                              TrajectoryFraudModel model,
                              @Qualifier("fieldCollectionExecutor") Executor executor) {
        this.store = store;
        this.properties = properties;
        this.model = model;
        this.executor = executor;
    }

    public void record(UUID deliveryId, UUID courierId, boolean consented,
                       FieldRunSource source, Double latitude, Double longitude,
                       TrajectoryFraudService.Verdict verdict, Instant recordedAt) {

        if (verdict == null || latitude == null || longitude == null) {
            return;
        }
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.isRequireConsent() && !consented) {
            return;
        }

        FieldRunSource effectiveSource = source == null ? FieldRunSource.FIELD : source;

        try {
            String modelVersion = "rf-" + model.treeCount() + "t@" + model.threshold();
            executor.execute(() -> {
                try {
                    store.record(deliveryId, courierId, effectiveSource,
                            latitude, longitude, verdict, recordedAt, modelVersion);
                } catch (Exception e) {
                    // This runs on the field-collection thread, after the
                    // @Transactional proxy has rolled the write back.
                    log.warn("field collection write failed for delivery {}: {}",
                            deliveryId, e.toString());
                }
            });
        } catch (TaskRejectedException e) {
            log.warn("field collection queue full; dropped trace for delivery {}", deliveryId);
        } catch (RuntimeException e) {
            log.warn("field collection dispatch failed for delivery {}: {}",
                    deliveryId, e.toString());
        }
    }
}
