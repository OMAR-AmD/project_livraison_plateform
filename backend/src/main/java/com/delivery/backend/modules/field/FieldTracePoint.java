package com.delivery.backend.modules.field;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une ligne de features pour un fix GPS.
 *
 * <p>Les huit colonnes de features sont exactement celles du modèle
 * ({@code TrajectoryFraudService.Verdict.features()}) : c'est ce qui rend le
 * run réutilisable pour un réentraînement sans rejouer les trajectoires.
 */
@Entity
@Table(name = "field_trace_points",
        indexes = {
                @Index(name = "idx_field_trace_run", columnList = "run_id"),
                @Index(name = "idx_field_trace_recorded", columnList = "recorded_at")
        })
public class FieldTracePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private FieldRun run;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /** Coordonnée volontairement arrondie à ~50 m, jamais l'adresse brute. */
    @Column(name = "lat", nullable = false)
    private double latitude;

    @Column(name = "lng", nullable = false)
    private double longitude;

    @Column(name = "speed_mps", nullable = false)
    private double speedMps;

    @Column(name = "accel_mps2", nullable = false)
    private double accelMps2;

    @Column(name = "dist_to_target_m", nullable = false)
    private double distanceToTargetM;

    @Column(name = "progress_ratio", nullable = false)
    private double progressRatio;

    @Column(name = "heading_change_deg", nullable = false)
    private double headingChangeDeg;

    @Column(name = "target_bearing_err", nullable = false)
    private double targetBearingErr;

    @Column(name = "stall_seconds", nullable = false)
    private double stallSeconds;

    @Column(name = "progress_rate", nullable = false)
    private double progressRate;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Column(name = "fraud", nullable = false)
    private boolean fraud;

    @Column(name = "run_flagged", nullable = false)
    private boolean runFlagged;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    public FieldTracePoint() {}

    public FieldTracePoint(FieldRun run, LocalDateTime recordedAt,
                           double latitude, double longitude,
                           double[] features, double score, double threshold,
                           boolean fraud, boolean runFlagged, String modelVersion) {
        if (features == null || features.length != 8) {
            throw new IllegalArgumentException("a field trace point needs exactly 8 features");
        }
        this.run = run;
        this.recordedAt = recordedAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedMps = features[0];
        this.accelMps2 = features[1];
        this.distanceToTargetM = features[2];
        this.progressRatio = features[3];
        this.headingChangeDeg = features[4];
        this.targetBearingErr = features[5];
        this.stallSeconds = features[6];
        this.progressRate = features[7];
        this.score = score;
        this.threshold = threshold;
        this.fraud = fraud;
        this.runFlagged = runFlagged;
        this.modelVersion = modelVersion;
    }

    public UUID getId() { return id; }
    public FieldRun getRun() { return run; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getSpeedMps() { return speedMps; }
    public double getAccelMps2() { return accelMps2; }
    public double getDistanceToTargetM() { return distanceToTargetM; }
    public double getProgressRatio() { return progressRatio; }
    public double getHeadingChangeDeg() { return headingChangeDeg; }
    public double getTargetBearingErr() { return targetBearingErr; }
    public double getStallSeconds() { return stallSeconds; }
    public double getProgressRate() { return progressRate; }
    public double getScore() { return score; }
    public double getThreshold() { return threshold; }
    public boolean isFraud() { return fraud; }
    public boolean isRunFlagged() { return runFlagged; }
    public String getModelVersion() { return modelVersion; }
}
