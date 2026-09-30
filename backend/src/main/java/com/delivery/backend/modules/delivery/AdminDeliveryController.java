package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.ai.FraudTrailStore;
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
    private final FraudTrailStore fraudTrailStore;

    public AdminDeliveryController(DeliveryService deliveryService, RouteOptimizationService routeOptimizationService, FraudTrailStore fraudTrailStore) {
        this.deliveryService = deliveryService;
        this.routeOptimizationService = routeOptimizationService;
        this.fraudTrailStore = fraudTrailStore;
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

    @PatchMapping("/{id}/status")
    public ResponseEntity<DeliveryResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody com.delivery.backend.modules.delivery.dto.DeliveryStatusUpdateRequest request) {
        return ResponseEntity.ok(deliveryService.adminUpdateStatus(id, request.getStatus()));
    }

    /**
     * Returns a delivery to the unassigned pool. The delivery goes back to
     * PENDING with no courier, so the next paid order — or another manual
     * assignment — can pick it up.
     */
    @DeleteMapping("/{id}/assign")
    public ResponseEntity<DeliveryResponse> unassignCourier(@PathVariable UUID id) {
        return ResponseEntity.ok(deliveryService.unassignCourier(id));
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

    /**
     * Badge « trajectoire suspecte » pour chaque livraison encore en mémoire.
     *
     * <p>Exposé pour que le modèle soit visible dans le produit et pas seulement
     * dans les logs : sans cette lecture, la détection n'existe que pour le
     * client qui reçoit l'avertissement, et un superviseur n'a aucun moyen de la
     * voir avant qu'elle ne coûte cher.
     *
     * <p>Une seule requête pour tout le tableau de bord, pas une par ligne :
     * la liste se rafraîchit toutes les 10 s.
     *
     * @return un résumé par livraison scorée, vide si rien ne l'a été
     */
    @GetMapping("/fraud-trails")
    public ResponseEntity<List<FraudTrailStore.Summary>> getFraudTrailSummaries() {
        return ResponseEntity.ok(fraudTrailStore.allTrails().stream()
                .map(FraudTrailStore.Trail::summary)
                .toList());
    }

    /**
     * La courbe complète d'une livraison : un point par broadcast de position.
     *
     * <p>409 si la livraison n'a jamais été scorée, ce qui est la réponse
     * honnête — un tableau de bord qui afficherait une courbe plate pour une
     * livraison que le modèle n'a jamais vue ferait croire à une mesure.
     */
    @GetMapping("/{id}/fraud-trail")
    public ResponseEntity<FraudTrailStore.Trail> getFraudTrail(@PathVariable UUID id) {
        FraudTrailStore.Trail trail = fraudTrailStore.trail(id);
        if (trail == null) {
            return ResponseEntity.status(409).build();
        }
        return ResponseEntity.ok(trail);
    }

    /**
     * Re-verifies the sealed proof of delivery for a delivery and reports whether
     * it still holds.
     *
     * <p>This is what turns the seal from a stored string into a check. The seal
     * is only useful if someone can ask "is this still intact?" and get an answer
     * the platform computed, not one a person typed.
     *
     * <p>409 when the delivery was never sealed. Reporting a delivery with no
     * proof as {@code verified: true} would be the security equivalent of a check
     * that cannot fail; reporting it as {@code verified: false} would accuse an
     * honest courier of tampering. Neither is acceptable, so the absence is its
     * own answer.
     */
    @GetMapping("/{id}/proof")
    public ResponseEntity<DeliveryService.DeliveryProofView> getProof(@PathVariable UUID id) {
        DeliveryService.DeliveryProofView proof = deliveryService.getProof(id);
        if (proof == null) {
            return ResponseEntity.status(409).build();
        }
        return ResponseEntity.ok(proof);
    }
}
