package com.delivery.backend.modules.ai;

import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.delivery.DeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;
import java.util.function.Function;

@Configuration
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    public record CancelDeliveryRequest(String deliveryId) {}
    public record CancelDeliveryResponse(String status, String message) {}

    @Bean
    @Description("Annuler une commande de livraison en utilisant son ID (UUID).")
    public Function<CancelDeliveryRequest, CancelDeliveryResponse> cancelDeliveryFunction(DeliveryService deliveryService) {
        return request -> {
            // Resolve WHO is asking. The model must never be trusted to decide this:
            // it only supplies an ID, and a 1B model will happily echo back an ID it
            // invented or one belonging to somebody else. Every authorisation
            // decision is therefore made here, from the authenticated principal.
            // /api/v1/client/** already requires the CLIENT role, so a missing or
            // non-User principal means the request did not come from a real client.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof User client)) {
                log.warn("Rejected cancelDelivery tool call from an unauthenticated principal");
                return new CancelDeliveryResponse("ERROR", "Action refusée : utilisateur non authentifié.");
            }

            String rawId = request.deliveryId();
            if (rawId == null || rawId.isBlank()) {
                return new CancelDeliveryResponse("ERROR", "Aucun identifiant de commande n'a été fourni.");
            }

            try {
                UUID id = UUID.fromString(rawId.trim());
                // cancelDelivery() — deliberately NOT deleteDelivery().
                // cancelDelivery() verifies the delivery belongs to this client and
                // only allows cancelling while PENDING. deleteDelivery() hard-deletes
                // the row and performs no ownership check whatsoever, so calling it
                // from a language model meant any client could destroy any delivery.
                deliveryService.cancelDelivery(id, client);
                return new CancelDeliveryResponse("SUCCESS",
                        "La commande " + id + " a été annulée avec succès.");
            } catch (IllegalArgumentException e) {
                // Domain rejections: unknown ID, not the owner, or not PENDING.
                // Surfaced verbatim so the assistant can explain what went wrong
                // instead of answering a bare "ERROR" to the user.
                return new CancelDeliveryResponse("ERROR", "Annulation impossible : " + e.getMessage());
            } catch (Exception e) {
                log.error("cancelDelivery tool call failed", e);
                return new CancelDeliveryResponse("ERROR",
                        "Impossible d'annuler la commande. L'ID est peut-être invalide ou la commande est déjà traitée.");
            }
        };
    }

    public record DeliveryDetailsRequest(String deliveryId) {}
    public record DeliveryDetailsResponse(String status, String description, String pickupAddress,
            String dropoffAddress, String orderStatus, Double price, String paymentStatus,
            String courierEmail, String message) {}

    @Bean
    @Description("Read the live status and details of one of the customer's own deliveries by its ID (UUID).")
    public Function<DeliveryDetailsRequest, DeliveryDetailsResponse> deliveryDetailsFunction(DeliveryService deliveryService) {
        return request -> {
            // Same rule as cancellation. The model supplies only an ID, and a
            // small model will happily echo back an ID it invented or one
            // belonging to somebody else. Every authorisation decision is
            // therefore made here, from the authenticated principal.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof User client)) {
                log.warn("Rejected deliveryDetails tool call from an unauthenticated principal");
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        "Action refused: unauthenticated user.");
            }

            String rawId = request.deliveryId();
            if (rawId == null || rawId.isBlank()) {
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        "No order ID was supplied.");
            }

            try {
                UUID id = UUID.fromString(rawId.trim());
                var d = deliveryService.getDeliveryForClient(id, client);
                return new DeliveryDetailsResponse("SUCCESS", d.getDescription(),
                        d.getPickupAddress(), d.getDropoffAddress(),
                        d.getStatus() != null ? d.getStatus().name() : null,
                        d.getPrice(), d.getPaymentStatus(), d.getCourierEmail(), null);
            } catch (IllegalArgumentException e) {
                // Unknown ID or someone else's order: the same generic answer,
                // so a guessed ID cannot probe whether an order exists.
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        e.getMessage());
            } catch (Exception e) {
                log.error("deliveryDetails tool call failed", e);
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        "Could not read the order. The ID may be invalid.");
            }
        };
    }
}
