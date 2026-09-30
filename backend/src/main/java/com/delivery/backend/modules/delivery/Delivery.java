package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String pickupAddress;

    @Column(nullable = false)
    private String dropoffAddress;

    @Column(name = "pickup_lat")
    private Double pickupLat;

    @Column(name = "pickup_lng")
    private Double pickupLng;

    @Column(name = "dropoff_lat")
    private Double dropoffLat;

    @Column(name = "dropoff_lng")
    private Double dropoffLng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "review_comment", length = 1000)
    private String reviewComment;

    @Column(name = "price")
    private Double price;

    @Column(name = "payment_status")
    private String paymentStatus;

    // Sprint 1 — Preuve de livraison cryptographique (anti-répudiation).
    // Position GPS réelle du livreur à l'instant du passage à DELIVERED,
    // horodatage de la livraison, distance mesurée jusqu'à la destination,
    // et hash SHA-256 scellant (commande, livreur, client, position, instant).
    @Column(name = "delivered_lat")
    private Double deliveredLat;

    @Column(name = "delivered_lng")
    private Double deliveredLng;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "proof_distance_m")
    private Double proofDistanceM;

    @Column(name = "proof_hash", length = 64)
    private String proofHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private User client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courier_id")
    private User courier;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Delivery() {}

    public Delivery(String description, String pickupAddress, String dropoffAddress, User client) {
        this.description = description;
        this.pickupAddress = pickupAddress;
        this.dropoffAddress = dropoffAddress;
        this.client = client;
        this.status = DeliveryStatus.PENDING;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPickupAddress() { return pickupAddress; }
    public void setPickupAddress(String pickupAddress) { this.pickupAddress = pickupAddress; }
    public String getDropoffAddress() { return dropoffAddress; }
    public void setDropoffAddress(String dropoffAddress) { this.dropoffAddress = dropoffAddress; }
    public Double getPickupLat() { return pickupLat; }
    public void setPickupLat(Double pickupLat) { this.pickupLat = pickupLat; }
    public Double getPickupLng() { return pickupLng; }
    public void setPickupLng(Double pickupLng) { this.pickupLng = pickupLng; }
    public Double getDropoffLat() { return dropoffLat; }
    public void setDropoffLat(Double dropoffLat) { this.dropoffLat = dropoffLat; }
    public Double getDropoffLng() { return dropoffLng; }
    public void setDropoffLng(Double dropoffLng) { this.dropoffLng = dropoffLng; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public User getClient() { return client; }
    public void setClient(User client) { this.client = client; }
    public User getCourier() { return courier; }
    public void setCourier(User courier) { this.courier = courier; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public Double getDeliveredLat() { return deliveredLat; }
    public void setDeliveredLat(Double deliveredLat) { this.deliveredLat = deliveredLat; }
    public Double getDeliveredLng() { return deliveredLng; }
    public void setDeliveredLng(Double deliveredLng) { this.deliveredLng = deliveredLng; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
    public Double getProofDistanceM() { return proofDistanceM; }
    public void setProofDistanceM(Double proofDistanceM) { this.proofDistanceM = proofDistanceM; }
    public String getProofHash() { return proofHash; }
    public void setProofHash(String proofHash) { this.proofHash = proofHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
