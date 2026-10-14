package com.delivery.backend.modules.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A signed record of a deleted sealed delivery.
 *
 * <p>Deleting a sealed row tears its successor's link, so silent deletion and
 * chain verification cannot coexist: either deletes of sealed rows are refused
 * (and every rehearsal cleanup script plus the client's delete-history button
 * breaks), or the deletion itself becomes evidence. This is the second option.
 * The row is removed as requested, but a tombstone keeps a full copy of the
 * seal data at its original sequence number, plus when and by whom it was
 * deleted — all covered by its own HMAC. {@code verifySealChain} walks
 * tombstones in place of their rows, so a recorded deletion keeps the chain
 * green while a row deleted behind the service's back (no tombstone, no key
 * to forge one) still breaks it at the successor.
 */
@Entity
@Table(name = "seal_tombstone")
public class SealTombstone {

    /** The deleted delivery's id: one tombstone per delivery, ever. */
    @Id
    @Column(name = "delivery_id")
    private UUID deliveryId;

    @Column(name = "courier_id")
    private UUID courierId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "delivered_lat")
    private Double deliveredLat;

    @Column(name = "delivered_lng")
    private Double deliveredLng;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "proof_hash", length = 64, nullable = false)
    private String proofHash;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    /** The deleted row's chain position: the walk merges on this. */
    @Column(name = "seal_seq", nullable = false)
    private Long sealSeq;

    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    /** Who asked for the deletion: "admin", or the client's email. */
    @Column(name = "deleted_by")
    private String deletedBy;

    /** HMAC over the whole record above: a tombstone nobody can forge. */
    @Column(name = "tomb_seal", length = 64, nullable = false)
    private String tombSeal;

    public UUID getDeliveryId() { return deliveryId; }
    public void setDeliveryId(UUID deliveryId) { this.deliveryId = deliveryId; }
    public UUID getCourierId() { return courierId; }
    public void setCourierId(UUID courierId) { this.courierId = courierId; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public Double getDeliveredLat() { return deliveredLat; }
    public void setDeliveredLat(Double deliveredLat) { this.deliveredLat = deliveredLat; }
    public Double getDeliveredLng() { return deliveredLng; }
    public void setDeliveredLng(Double deliveredLng) { this.deliveredLng = deliveredLng; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
    public String getProofHash() { return proofHash; }
    public void setProofHash(String proofHash) { this.proofHash = proofHash; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public Long getSealSeq() { return sealSeq; }
    public void setSealSeq(Long sealSeq) { this.sealSeq = sealSeq; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
    public String getTombSeal() { return tombSeal; }
    public void setTombSeal(String tombSeal) { this.tombSeal = tombSeal; }
}
