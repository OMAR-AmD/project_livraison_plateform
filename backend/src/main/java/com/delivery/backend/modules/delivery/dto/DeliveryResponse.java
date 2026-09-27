package com.delivery.backend.modules.delivery.dto;

import com.delivery.backend.modules.delivery.Delivery;
import com.delivery.backend.modules.delivery.DeliveryStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public class DeliveryResponse {
    private UUID id;
    private String description;
    private String pickupAddress;
    private String dropoffAddress;
    private DeliveryStatus status;
    private String clientEmail;
    private String courierEmail;
    private UUID courierId;
    private Double pickupLat;
    private Double pickupLng;
    private Double dropoffLat;
    private Double dropoffLng;
    private Double courierLatitude;
    private Double courierLongitude;
    private Integer rating;
    private String reviewComment;
    private Double price;
    private String paymentStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public DeliveryResponse() {}

    public DeliveryResponse(Delivery delivery) {
        this.id = delivery.getId();
        this.description = delivery.getDescription();
        this.pickupAddress = delivery.getPickupAddress();
        this.dropoffAddress = delivery.getDropoffAddress();
        this.status = delivery.getStatus();
        this.clientEmail = delivery.getClient() != null ? delivery.getClient().getEmail() : null;
        this.courierEmail = delivery.getCourier() != null ? delivery.getCourier().getEmail() : null;
        this.courierId = delivery.getCourier() != null ? delivery.getCourier().getId() : null;
        this.pickupLat = delivery.getPickupLat();
        this.pickupLng = delivery.getPickupLng();
        this.dropoffLat = delivery.getDropoffLat();
        this.dropoffLng = delivery.getDropoffLng();
        this.rating = delivery.getRating();
        this.reviewComment = delivery.getReviewComment();
        this.price = delivery.getPrice();
        this.paymentStatus = delivery.getPaymentStatus();
        this.createdAt = delivery.getCreatedAt();
        this.updatedAt = delivery.getUpdatedAt();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPickupAddress() { return pickupAddress; }
    public void setPickupAddress(String pickupAddress) { this.pickupAddress = pickupAddress; }
    public String getDropoffAddress() { return dropoffAddress; }
    public void setDropoffAddress(String dropoffAddress) { this.dropoffAddress = dropoffAddress; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public String getClientEmail() { return clientEmail; }
    public void setClientEmail(String clientEmail) { this.clientEmail = clientEmail; }
    public String getCourierEmail() { return courierEmail; }
    public void setCourierEmail(String courierEmail) { this.courierEmail = courierEmail; }
    public UUID getCourierId() { return courierId; }
    public void setCourierId(UUID courierId) { this.courierId = courierId; }
    public Double getPickupLat() { return pickupLat; }
    public void setPickupLat(Double pickupLat) { this.pickupLat = pickupLat; }
    public Double getPickupLng() { return pickupLng; }
    public void setPickupLng(Double pickupLng) { this.pickupLng = pickupLng; }
    public Double getDropoffLat() { return dropoffLat; }
    public void setDropoffLat(Double dropoffLat) { this.dropoffLat = dropoffLat; }
    public Double getDropoffLng() { return dropoffLng; }
    public void setDropoffLng(Double dropoffLng) { this.dropoffLng = dropoffLng; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Double getCourierLatitude() { return courierLatitude; }
    public void setCourierLatitude(Double courierLatitude) { this.courierLatitude = courierLatitude; }
    public Double getCourierLongitude() { return courierLongitude; }
    public void setCourierLongitude(Double courierLongitude) { this.courierLongitude = courierLongitude; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
}
