package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.delivery.dto.CourierAssignmentRequest;
import com.delivery.backend.modules.delivery.dto.DeliveryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/deliveries")
public class AdminDeliveryController {

    private final DeliveryService deliveryService;
    private final RouteOptimizationService routeOptimizationService;

    public AdminDeliveryController(DeliveryService deliveryService, RouteOptimizationService routeOptimizationService) {
        this.deliveryService = deliveryService;
        this.routeOptimizationService = routeOptimizationService;
    }

    @GetMapping
    public ResponseEntity<List<DeliveryResponse>> getAllDeliveries() {
        return ResponseEntity.ok(deliveryService.getAllDeliveries());
    }

    @PatchMapping("/{id}/assign")
    public ResponseEntity<DeliveryResponse> assignCourier(
            @PathVariable UUID id,
            @Valid @RequestBody CourierAssignmentRequest request) {
        return ResponseEntity.ok(deliveryService.assignCourier(id, request.getCourierId()));
    }

    @GetMapping("/{id}/location")
    public ResponseEntity<com.delivery.backend.modules.delivery.dto.LocationUpdateRequest> getCourierLocation(
            @PathVariable UUID id) {
        // Admin can fetch location of any delivery
        com.delivery.backend.modules.delivery.dto.LocationUpdateRequest loc = deliveryService.getCourierLocation(id);
        if (loc == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(loc);
    }

    @GetMapping("/couriers/{courierId}/route")
    public ResponseEntity<RouteOptimizationResponse> getCourierRoute(
            @PathVariable UUID courierId,
            @RequestParam double lat,
            @RequestParam double lng) {
        com.delivery.backend.modules.auth.User courier = deliveryService.getUserById(courierId);
        if (courier == null) {
            return ResponseEntity.notFound().build();
        }
        List<Delivery> courierDeliveries = deliveryService.getRawDeliveriesForCourier(courier)
            .stream()
            .filter(d -> d.getStatus() == DeliveryStatus.ASSIGNED || d.getStatus() == DeliveryStatus.IN_TRANSIT)
            .toList();
            
        return ResponseEntity.ok(routeOptimizationService.optimizeDeliveries(lat, lng, courierDeliveries));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDelivery(@PathVariable UUID id) {
        deliveryService.deleteDelivery(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<DeliveryService.AdminStatsDTO> getAdminStats() {
        return ResponseEntity.ok(deliveryService.getAdminStats());
    }

    @GetMapping("/activities")
    public ResponseEntity<List<DeliveryService.AdminActivityDTO>> getAdminActivities() {
        return ResponseEntity.ok(deliveryService.getAdminActivities());
    }
}
