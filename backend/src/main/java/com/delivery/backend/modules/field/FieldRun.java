package com.delivery.backend.modules.field;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un run terrain : une livraison suivie, avec ses points GPS et son label.
 *
 * <p>Une ligne par livraison. Le label est volontairement porté par le run et
 * non par chaque point : un coursier est honnête ou frauduleux sur une tournée,
 * pas fix par fix. Les features sont dans {@link FieldTracePoint}.
 */
@Entity
@Table(name = "field_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_field_runs_delivery", columnNames = "delivery_id"))
public class FieldRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, unique = true)
    private UUID deliveryId;

    @Column(name = "courier_id", nullable = false)
    private UUID courierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private FieldRunSource source;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_fix_at")
    private LocalDateTime lastFixAt;

    @Column(name = "point_count", nullable = false)
    private int pointCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", length = 32)
    private FieldLabel label;

    @Column(name = "label_source", length = 64)
    private String labelSource;

    @Column(name = "labeled_at")
    private LocalDateTime labeledAt;

    @Column(name = "labeled_by")
    private UUID labeledBy;

    @Column(name = "consent_version", length = 32)
    private String consentVersion;

    @Column(name = "retention_until", nullable = false)
    private LocalDateTime retentionUntil;

    public FieldRun() {}

    public FieldRun(UUID deliveryId, UUID courierId, FieldRunSource source,
                    LocalDateTime startedAt, String consentVersion, int retentionDays) {
        this.deliveryId = deliveryId;
        this.courierId = courierId;
        this.source = source;
        this.startedAt = startedAt;
        this.lastFixAt = startedAt;
        this.pointCount = 0;
        this.consentVersion = consentVersion;
        this.retentionUntil = startedAt.plusDays(retentionDays);
    }

    public UUID getId() { return id; }
    public UUID getDeliveryId() { return deliveryId; }
    public UUID getCourierId() { return courierId; }
    public FieldRunSource getSource() { return source; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getLastFixAt() { return lastFixAt; }
    public int getPointCount() { return pointCount; }
    public void setPointCount(int pointCount) { this.pointCount = pointCount; }
    public void setLastFixAt(LocalDateTime lastFixAt) { this.lastFixAt = lastFixAt; }
    public FieldLabel getLabel() { return label; }
    public void setLabel(FieldLabel label) { this.label = label; }
    public String getLabelSource() { return labelSource; }
    public void setLabelSource(String labelSource) { this.labelSource = labelSource; }
    public LocalDateTime getLabeledAt() { return labeledAt; }
    public void setLabeledAt(LocalDateTime labeledAt) { this.labeledAt = labeledAt; }
    public UUID getLabeledBy() { return labeledBy; }
    public void setLabeledBy(UUID labeledBy) { this.labeledBy = labeledBy; }
    public String getConsentVersion() { return consentVersion; }
    public LocalDateTime getRetentionUntil() { return retentionUntil; }
}
