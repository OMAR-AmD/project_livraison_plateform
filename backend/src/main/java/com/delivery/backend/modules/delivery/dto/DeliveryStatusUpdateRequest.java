package com.delivery.backend.modules.delivery.dto;

import com.delivery.backend.modules.delivery.DeliveryStatus;
import jakarta.validation.constraints.NotNull;

public class DeliveryStatusUpdateRequest {

    @NotNull(message = "Status is required")
    private DeliveryStatus status;

    public DeliveryStatusUpdateRequest() {}

    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
}
