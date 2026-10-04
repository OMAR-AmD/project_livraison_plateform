package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.delivery.dto.DeliveryResponse;
import com.delivery.backend.modules.delivery.dto.DeliveryStatusUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/courier/deliveries")
public class CourierDeliveryController {

    private final DeliveryService deliveryService;
    private final RouteOptimizationService routeOptimizationService;

    public CourierDeliveryController(DeliveryService deliveryService, RouteOptimizationService routeOptimizationService) {
        this.deliveryService = deliveryService;
        this.routeOptimizationService = routeOptimizationService;
    }

    @GetMapping
    public ResponseEntity<List<DeliveryResponse>> getMyAssignedDeliveries(
            @AuthenticationPrincipal User courier) {
        return ResponseEntity.ok(deliveryService.getDeliveriesForCourier(courier));
    }

    /**
     * The caller's own track record: deliveries completed, still active, cancelled,
     * and their average rating.
     *
     * <p>Scoped to the authenticated courier — there is no id parameter, so one
     * courier cannot read another's numbers by changing the URL.
     */
    @GetMapping("/stats")
    public ResponseEntity<DeliveryService.CourierStatsDTO> getMyStats(
            @AuthenticationPrincipal User courier) {
        return ResponseEntity.ok(deliveryService.getCourierStats(courier));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<DeliveryResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody DeliveryStatusUpdateRequest request,
            @AuthenticationPrincipal User courier) {
        return ResponseEntity.ok(deliveryService.updateDeliveryStatus(
                id, request.getStatus(), request.getLatitude(), request.getLongitude(),
                request.getHandoverCode(), courier));
    }

    @PatchMapping("/{id}/location")
    public ResponseEntity<Void> updateLocation(
            @PathVariable UUID id,
            @Valid @RequestBody com.delivery.backend.modules.delivery.dto.LocationUpdateRequest request,
            @AuthenticationPrincipal User courier) {
        deliveryService.updateCourierLocation(id, request.getLatitude(), request.getLongitude(), courier);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/optimize")
    public ResponseEntity<RouteOptimizationResponse> optimizeMyRoute(
            @RequestParam double lat,
            @RequestParam double lng,
            @AuthenticationPrincipal User courier) {
        
        List<Delivery> myDeliveries = deliveryService.getRawDeliveriesForCourier(courier)
            .stream()
            .filter(d -> d.getStatus() == DeliveryStatus.ASSIGNED || d.getStatus() == DeliveryStatus.IN_TRANSIT || d.getStatus() == DeliveryStatus.ARRIVED)
            .toList();

        return ResponseEntity.ok(routeOptimizationService.optimizeDeliveries(lat, lng, myDeliveries));
    }
}
