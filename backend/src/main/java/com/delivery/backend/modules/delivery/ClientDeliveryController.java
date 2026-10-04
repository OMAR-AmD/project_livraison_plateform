package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.delivery.dto.DeliveryQuoteResponse;
import com.delivery.backend.modules.delivery.dto.DeliveryRequest;
import com.delivery.backend.modules.delivery.dto.DeliveryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/client/deliveries")
public class ClientDeliveryController {

    private final DeliveryService deliveryService;

    public ClientDeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @PostMapping
    public ResponseEntity<DeliveryResponse> createDelivery(
            @Valid @RequestBody DeliveryRequest request,
            @AuthenticationPrincipal User client) {
        DeliveryResponse response = deliveryService.createDelivery(request, client);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Prices a delivery without creating it, so the amount shown at checkout is the
     * amount the server will charge. The client never supplies a price.
     */
    @PostMapping("/quote")
    public ResponseEntity<DeliveryQuoteResponse> quoteDelivery(
            @Valid @RequestBody DeliveryRequest request,
            @AuthenticationPrincipal User client) {
        return ResponseEntity.ok(deliveryService.quoteDelivery(request));
    }

    /**
     * Captures payment for a created order. Only after this succeeds does the order
     * count towards revenue and get handed to the auto-dispatcher.
     */
    @PatchMapping("/{id}/pay")
    public ResponseEntity<DeliveryResponse> payDelivery(
            @PathVariable UUID id,
            @AuthenticationPrincipal User client) {
        return ResponseEntity.ok(deliveryService.capturePayment(id, client));
    }

    @GetMapping
    public ResponseEntity<List<DeliveryResponse>> getMyDeliveries(
            @AuthenticationPrincipal User client) {
        return ResponseEntity.ok(deliveryService.getDeliveriesForClient(client));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<DeliveryResponse> cancelDelivery(
            @PathVariable UUID id,
            @AuthenticationPrincipal User client) {
        return ResponseEntity.ok(deliveryService.cancelDelivery(id, client));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDelivery(
            @PathVariable UUID id,
            @AuthenticationPrincipal User client) {
        deliveryService.deleteMyDelivery(id, client);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/location")
    public ResponseEntity<com.delivery.backend.modules.delivery.dto.LocationUpdateRequest> getCourierLocation(
            @PathVariable UUID id,
            @AuthenticationPrincipal User client) {
        com.delivery.backend.modules.delivery.dto.LocationUpdateRequest loc = deliveryService.getCourierLocationForClient(id, client);
        if (loc == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(loc);
    }

    @PostMapping("/{id}/rate")
    public ResponseEntity<DeliveryResponse> rateDelivery(
            @PathVariable UUID id,
            @Valid @RequestBody com.delivery.backend.modules.delivery.dto.RatingRequest request,
            @AuthenticationPrincipal User client) {
        return ResponseEntity.ok(deliveryService.rateDelivery(id, request.getRating(), request.getReviewComment(), client));
    }

    /**
     * The six-digit handover code the recipient shows at the door. Owner-only:
     * issued to the client who owns the order, verified from the courier side.
     */
    @GetMapping("/{id}/handover-code")
    public ResponseEntity<Map<String, String>> handoverCode(
            @PathVariable UUID id,
            @AuthenticationPrincipal User client) {
        return ResponseEntity.ok(Map.of("code", deliveryService.getHandoverCode(id, client)));
    }
}
