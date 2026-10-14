package com.delivery.backend.modules.ai;

import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.delivery.DeliveryService;
import com.delivery.backend.modules.delivery.DeliveryStatus;
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

    public record ProposeCancellationRequest(String deliveryId) {}
    public record ProposeCancellationResponse(String status, String message) {}
    public record ConfirmCancellationRequest(String deliveryId) {}
    public record ConfirmCancellationResponse(String status, String message) {}

    /**
     * Step one of cancelling: records the intent, changes nothing.
     *
     * <p>The model proved it will cancel an order the customer never named, so
     * "the model asked, therefore the customer agreed" is not a premise this
     * codebase accepts. The intent is stored per client and only an explicit
     * confirmation of the same order executes it (see
     * {@code confirmCancellationFunction}). Ownership and PENDING-only are
     * checked here for a good error message, and again at execution against
     * the order as it stands then.
     */
    @Bean
    @Description("Propose cancelling one of the customer's own PENDING deliveries by its ID (UUID). This records the intent and changes nothing: the customer must then explicitly confirm that exact order before anything happens.")
    public Function<ProposeCancellationRequest, ProposeCancellationResponse> proposeCancellationFunction(
            DeliveryService deliveryService, CancelProposalStore proposals) {
        return request -> {
            // Resolve WHO is asking. The model must never be trusted to decide this:
            // it only supplies an ID, and a small model will happily echo back an ID it
            // invented or one belonging to somebody else. Every authorisation
            // decision is therefore made here, from the authenticated principal.
            // /api/v1/client/** already requires the CLIENT role, so a missing or
            // non-User principal means the request did not come from a real client.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof User client)) {
                log.warn("Rejected proposeCancellation tool call from an unauthenticated principal");
                return new ProposeCancellationResponse("ERROR", "Action refusée : utilisateur non authentifié.");
            }

            String rawId = request.deliveryId();
            if (rawId == null || rawId.isBlank()) {
                return new ProposeCancellationResponse("ERROR", "Aucun identifiant de commande n'a été fourni.");
            }

            try {
                UUID id = UUID.fromString(rawId.trim());
                var order = deliveryService.getDeliveryForClient(id, client);
                if (order.getStatus() != DeliveryStatus.PENDING) {
                    return new ProposeCancellationResponse("ERROR",
                            "Seule une commande en attente (PENDING) peut être annulée.");
                }
                proposals.propose(client.getId(), id);
                return new ProposeCancellationResponse("PROPOSED",
                        "Intention enregistrée pour la commande " + id + ". Demandez au client "
                                + "de confirmer explicitement cette commande précise avant d'appeler "
                                + "la confirmation.");
            } catch (IllegalArgumentException e) {
                // Unknown ID or someone else's order (both read as "not found", so a
                // guessed ID cannot probe whether an order exists). Surfaced verbatim
                // so the assistant can explain instead of answering a bare "ERROR".
                return new ProposeCancellationResponse("ERROR", "Annulation impossible : " + e.getMessage());
            } catch (Exception e) {
                log.error("proposeCancellation tool call failed", e);
                return new ProposeCancellationResponse("ERROR",
                        "Impossible de proposer l'annulation. L'ID est peut-être invalide.");
            }
        };
    }

    /**
     * Step two of cancelling: executes only a live, matching proposal.
     *
     * <p>Calling this with no proposal — the model skipping the question — or
     * with a different ID than proposed is refused, and the store is built so
     * that refusal path mutates nothing. A blank ID confirms whatever is
     * pending: the chat API is stateless, so a bare "yes" carries no order ID,
     * and there is at most one proposal per client. {@code cancelDelivery}
     * re-verifies ownership and PENDING-only at execution, so an order
     * delivered between the proposal and the "yes" is refused rather than
     * cancelled late.
     */
    @Bean
    @Description("Execute a previously proposed cancellation. Call only after the customer explicitly confirmed; with no ID it confirms their pending proposal, with an ID that ID must match the proposal. Without a matching live proposal this refuses.")
    public Function<ConfirmCancellationRequest, ConfirmCancellationResponse> confirmCancellationFunction(
            DeliveryService deliveryService, CancelProposalStore proposals) {
        return request -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof User client)) {
                log.warn("Rejected confirmCancellation tool call from an unauthenticated principal");
                return new ConfirmCancellationResponse("ERROR", "Action refusée : utilisateur non authentifié.");
            }

            String rawId = request.deliveryId();
            try {
                final UUID confirmed;
                if (rawId == null || rawId.isBlank()) {
                    confirmed = proposals.consumePending(client.getId()).orElse(null);
                    if (confirmed == null) {
                        return new ConfirmCancellationResponse("ERROR",
                                "Aucune annulation en attente : proposez d'abord, "
                                        + "faites confirmer le client, puis confirmez.");
                    }
                } else {
                    UUID id = UUID.fromString(rawId.trim());
                    if (proposals.consume(client.getId(), id).isEmpty()) {
                        return new ConfirmCancellationResponse("ERROR",
                                "Aucune annulation en attente pour cette commande : proposez d'abord, "
                                        + "faites confirmer le client, puis confirmez.");
                    }
                    confirmed = id;
                }
                // cancelDelivery() — deliberately NOT deleteDelivery().
                // cancelDelivery() verifies the delivery belongs to this client and
                // only allows cancelling while PENDING. deleteDelivery() hard-deletes
                // the row and performs no ownership check whatsoever, so calling it
                // from a language model meant any client could destroy any delivery.
                deliveryService.cancelDelivery(confirmed, client);
                return new ConfirmCancellationResponse("SUCCESS",
                        "La commande " + confirmed + " a été annulée avec succès.");
            } catch (IllegalArgumentException e) {
                return new ConfirmCancellationResponse("ERROR", "Annulation impossible : " + e.getMessage());
            } catch (Exception e) {
                log.error("confirmCancellation tool call failed", e);
                return new ConfirmCancellationResponse("ERROR",
                        "Impossible d'annuler la commande. L'ID est peut-être invalide ou la commande est déjà traitée.");
            }
        };
    }

    public record DeliveryDetailsRequest(String deliveryId) {}
    public record DeliveryDetailsResponse(String status, String description, String pickupAddress,
            String dropoffAddress, String orderStatus, Double price, String paymentStatus,
            String courierEmail, Double courierLatitude, Double courierLongitude,
            Double courierSpeedMps, Double distanceToTargetM, Long positionAgeSeconds,
            String message) {}

    @Bean
    @Description("Read the live status and details of one of the customer's own deliveries by its ID (UUID).")
    public Function<DeliveryDetailsRequest, DeliveryDetailsResponse> deliveryDetailsFunction(
            DeliveryService deliveryService, FraudTrailStore fraudTrailStore) {
        return request -> {
            // Same rule as cancellation. The model supplies only an ID, and a
            // small model will happily echo back an ID it invented or one
            // belonging to somebody else. Every authorisation decision is
            // therefore made here, from the authenticated principal.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof User client)) {
                log.warn("Rejected deliveryDetails tool call from an unauthenticated principal");
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        null, null, null, null, null, "Action refused: unauthenticated user.");
            }

            String rawId = request.deliveryId();
            if (rawId == null || rawId.isBlank()) {
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        null, null, null, null, null, "No order ID was supplied.");
            }

            try {
                UUID id = UUID.fromString(rawId.trim());
                var d = deliveryService.getDeliveryForClient(id, client);

                // Live telemetry, best effort: the newest scored fix carries
                // speed, distance-to-target and freshness; the live endpoint
                // carries the coordinates. Anything missing stays null and the
                // prompt tells the model to say so rather than invent it.
                Double lat = null, lng = null, speed = null, dist = null;
                Long ageS = null;
                try {
                    var trail = fraudTrailStore.trail(id);
                    if (trail != null && !trail.points().isEmpty()) {
                        var newest = trail.points().get(trail.points().size() - 1);
                        speed = newest.speedMps();
                        dist = newest.distanceToTargetM();
                        ageS = Math.max(0, (System.currentTimeMillis() - newest.at()) / 1000);
                    }
                } catch (Exception e) {
                    log.warn("deliveryDetails telemetry read failed for {}: {}", id, e.toString());
                }
                try {
                    var loc = deliveryService.getCourierLocationForClient(id, client);
                    if (loc != null) {
                        lat = loc.getLatitude();
                        lng = loc.getLongitude();
                    }
                } catch (Exception e) {
                    log.warn("deliveryDetails location read failed for {}: {}", id, e.toString());
                }

                return new DeliveryDetailsResponse("SUCCESS", d.getDescription(),
                        d.getPickupAddress(), d.getDropoffAddress(),
                        d.getStatus() != null ? d.getStatus().name() : null,
                        d.getPrice(), d.getPaymentStatus(), d.getCourierEmail(),
                        lat, lng, speed, dist, ageS, null);
            } catch (IllegalArgumentException e) {
                // Unknown ID or someone else's order: the same generic answer,
                // so a guessed ID cannot probe whether an order exists.
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        null, null, null, null, null, e.getMessage());
            } catch (Exception e) {
                log.error("deliveryDetails tool call failed", e);
                return new DeliveryDetailsResponse("ERROR", null, null, null, null, null, null, null,
                        null, null, null, null, null, "Could not read the order. The ID may be invalid.");
            }
        };
    }
}
