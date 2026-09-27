package com.delivery.backend.modules.delivery;

import java.util.UUID;

public class Waypoint {
    private UUID deliveryId;
    private String type; // "PICKUP" or "DROPOFF"
    private Double latitude;
    private Double longitude;
    private String description;
    private Integer step;

    public Waypoint() {}

    public Waypoint(UUID deliveryId, String type, Double latitude, Double longitude, String description, Integer step) {
        this.deliveryId = deliveryId;
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.description = description;
        this.step = step;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(UUID deliveryId) {
        this.deliveryId = deliveryId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }
}
