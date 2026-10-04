package com.delivery.backend.modules.delivery.dto;

import com.delivery.backend.modules.delivery.DeliveryStatus;
import jakarta.validation.constraints.NotNull;

public class DeliveryStatusUpdateRequest {

    @NotNull(message = "Status is required")
    private DeliveryStatus status;

    /**
     * Position GPS du livreur à l'instant du passage à DELIVERED (Sprint 1).
     * Optionnelle : si absente, le backend retombe sur la dernière position
     * diffusée via Redis. Si aucune position n'existe, la livraison est refusée.
     */
    private Double latitude;

    private Double longitude;

    /**
     * Six-digit handover code shown by the client at the door (Sprint Voie A).
     * Optional: absent means the GPS-only path. Present but wrong fails closed
     * with a clear message rather than silently falling back to GPS.
     */
    private String handoverCode;

    public DeliveryStatusUpdateRequest() {}

    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getHandoverCode() { return handoverCode; }
    public void setHandoverCode(String handoverCode) { this.handoverCode = handoverCode; }
}
