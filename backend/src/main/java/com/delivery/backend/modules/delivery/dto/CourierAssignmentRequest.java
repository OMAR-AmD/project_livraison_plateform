package com.delivery.backend.modules.delivery.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class CourierAssignmentRequest {

    @NotNull(message = "Courier ID is required")
    private UUID courierId;

    public CourierAssignmentRequest() {}

    public UUID getCourierId() { return courierId; }
    public void setCourierId(UUID courierId) { this.courierId = courierId; }
}
