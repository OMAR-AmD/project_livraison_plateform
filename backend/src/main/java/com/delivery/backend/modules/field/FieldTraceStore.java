package com.delivery.backend.modules.field;

import com.delivery.backend.modules.ai.TrajectoryFraudService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Écriture durable d'un point de trace.
 *
 * <p>Méthode séparée du recorder pour que la transaction soit appliquée par
 * Spring : le recorder ne fait que filtrer, soumettre et déléguer, et l'écriture
 * Postgres ne partage jamais la transaction de la requête GPS.
 */
@Service
public class FieldTraceStore {

    private static final Logger log = LoggerFactory.getLogger(FieldTraceStore.class);

    /** Grille d'arrondi d'environ 50 m : jamais l'adresse brute du client. */
    private static final double COORDINATE_GRID_DEG = 0.0005;

    private final FieldRunRepository runRepository;
    private final FieldTracePointRepository pointRepository;
    private final FieldCollectionProperties properties;

    public FieldTraceStore(FieldRunRepository runRepository,
                           FieldTracePointRepository pointRepository,
                           FieldCollectionProperties properties) {
        this.runRepository = runRepository;
        this.pointRepository = pointRepository;
        this.properties = properties;
    }

    @Transactional
    public void record(UUID deliveryId, UUID courierId, FieldRunSource source,
                       double latitude, double longitude,
                       TrajectoryFraudService.Verdict verdict,
                       Instant recordedAtInstant, String modelVersion) {

        LocalDateTime recordedAt = LocalDateTime.ofInstant(recordedAtInstant, ZoneId.systemDefault());

        FieldRun run = runRepository.findByDeliveryId(deliveryId).orElse(null);
        if (run == null) {
            run = runRepository.save(new FieldRun(
                    deliveryId, courierId, source, recordedAt,
                    properties.getConsentVersion(), properties.getRetentionDays()));
        } else if (run.getSource() != source) {
            // Un run ne doit pas mélanger une trace réelle et une simulation :
            // le label porte sur la tournée entière.
            log.warn("field run {} started as {}; dropping {} fix",
                    deliveryId, run.getSource(), source);
            return;
        }

        FieldTracePoint point = new FieldTracePoint(
                run, recordedAt,
                coarsen(latitude), coarsen(longitude),
                verdict.features(), verdict.score(), verdict.threshold(),
                verdict.fraud(), verdict.runFlagged(), modelVersion);

        pointRepository.save(point);
        run.setPointCount(run.getPointCount() + 1);
        run.setLastFixAt(recordedAt);
        runRepository.save(run);
    }

    /**
     * Arrondit une coordonnée sur une grille d'environ 50 m.
     *
     * <p>Visible pour le test de confidentialité : c'est cette fonction qui
     * garantit qu'on ne stocke pas la position exacte d'un domicile.
     */
    static double coarsen(double degrees) {
        return Math.round(degrees / COORDINATE_GRID_DEG) * COORDINATE_GRID_DEG;
    }
}
