package com.delivery.backend.modules.delivery.dto;

/**
 * Result of pricing a delivery. Produced by {@code PricingService#quote} and returned
 * to the client so the figure shown at checkout is the exact figure the server will
 * store when the order is created.
 */
public class DeliveryQuoteResponse {

    private Double distanceKm;
    private Long durationSeconds;
    private Double price;
    private double congestionFactor;
    private boolean exceedsTimeLimit;
    private int maxDurationSeconds;

    public DeliveryQuoteResponse() {}

    public DeliveryQuoteResponse(Double distanceKm, Long durationSeconds, Double price) {
        this.distanceKm = distanceKm;
        this.durationSeconds = durationSeconds;
        this.price = price;
    }

    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public double getCongestionFactor() { return congestionFactor; }
    public void setCongestionFactor(double congestionFactor) { this.congestionFactor = congestionFactor; }
    public boolean isExceedsTimeLimit() { return exceedsTimeLimit; }
    public void setExceedsTimeLimit(boolean exceedsTimeLimit) { this.exceedsTimeLimit = exceedsTimeLimit; }
    public int getMaxDurationSeconds() { return maxDurationSeconds; }
    public void setMaxDurationSeconds(int maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }
}
