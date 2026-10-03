package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.Role;
import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.auth.UserRepository;
import com.delivery.backend.modules.delivery.dto.DeliveryQuoteResponse;
import com.delivery.backend.modules.delivery.dto.DeliveryRequest;
import com.delivery.backend.modules.delivery.dto.DeliveryResponse;
import com.delivery.backend.modules.notification.NotificationService;
import com.delivery.backend.modules.ai.TrajectoryFraudService;
import com.delivery.backend.modules.ai.FraudTrailStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    /**
     * Fallback start position for a courier who has never broadcast a location.
     * Casablanca city centre, matching the platform's stated coverage area.
     */
    private static final double DEFAULT_COURIER_LAT = 33.5731;
    private static final double DEFAULT_COURIER_LNG = -7.5898;

    private final DeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RedisTemplate<String, String> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private final RouteOptimizationService routeOptimizationService;
    private final PricingService pricingService;
    private final TrajectoryFraudService trajectoryFraudService;
    private final FraudTrailStore fraudTrailStore;
    private final DeliveryProofService proofService;

    public DeliveryService(DeliveryRepository deliveryRepository, UserRepository userRepository, NotificationService notificationService, RedisTemplate<String, String> redisTemplate, SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper, RouteOptimizationService routeOptimizationService, PricingService pricingService, TrajectoryFraudService trajectoryFraudService, FraudTrailStore fraudTrailStore, DeliveryProofService proofService) {
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.routeOptimizationService = routeOptimizationService;
        this.pricingService = pricingService;
        this.trajectoryFraudService = trajectoryFraudService;
        this.fraudTrailStore = fraudTrailStore;
        this.proofService = proofService;
    }

    public void updateCourierLocation(UUID deliveryId, Double latitude, Double longitude, User courier) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (delivery.getCourier() == null || !delivery.getCourier().getId().equals(courier.getId())) {
            throw new IllegalArgumentException("You are not assigned to this delivery");
        }

        if (delivery.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw new IllegalArgumentException("Delivery must be IN_TRANSIT to update location");
        }

        try {
            String json = objectMapper.writeValueAsString(new com.delivery.backend.modules.delivery.dto.LocationUpdateRequest(latitude, longitude));
            redisTemplate.opsForValue().set("livreur:position:" + deliveryId, json, 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }

        messagingTemplate.convertAndSend("/topic/colis/" + deliveryId,
            new com.delivery.backend.modules.delivery.dto.LocationUpdateRequest(latitude, longitude));

        // AI fraud detection. Runs after the position is published so a detection
        // problem can never block a courier's broadcast -- losing a coordinate is
        // a worse failure than losing an alert, and the two must not share a fate.
        try {
            scoreForFraud(delivery, latitude, longitude, courier);
        } catch (Exception e) {
            log.warn("fraud scoring failed for delivery {}: {}", deliveryId, e.toString());
        }
    }

    /**
     * Scores one position update and raises an alert when the model says the
     * movement is not physically plausible.
     *
     * <p>Kept separate from {@link #updateCourierLocation} so the caller can
     * decide what a detection failure costs. It must not be allowed to cost a
     * courier their coordinates.
     */
    private void scoreForFraud(Delivery delivery, Double latitude, Double longitude, User courier) {
        if (latitude == null || longitude == null
                || delivery.getDropoffLat() == null || delivery.getDropoffLng() == null) {
            return;
        }

        TrajectoryFraudService.Verdict verdict = trajectoryFraudService.evaluate(
                delivery.getId(),
                latitude, longitude,
                delivery.getDropoffLat(), delivery.getDropoffLng());

        // Every fix's score is retained, not only the flagged ones, so the admin
        // panel can show the trajectory instead of a bare yes/no. The trail
        // never throws: a lost trail is cheaper than a lost alert.
        fraudTrailStore.record(delivery.getId(), verdict);

        if (!verdict.shouldNotify()) {
            return;
        }

        String message = String.format(Locale.ROOT,
                "Suspicious trajectory detected: score %.0f%% (threshold %.0f%%), "
                        + "%.0f m/s, %s, %.0f km from the drop-off.",
                verdict.score() * 100, verdict.threshold() * 100,
                verdict.speedMps(),
                verdict.stallSeconds() >= 90
                        ? String.format(Locale.ROOT, "no progress for %d min",
                                Math.round(verdict.stallSeconds() / 60))
                        : "moving",
                verdict.distanceToTargetM() / 1000.0);

        log.warn("fraud alert on delivery {} (courier {}): {}", delivery.getId(), courier.getId(), message);

        // The client sees an unverified-delivery notice rather than the internal
        // suspicion: what they need to know is that the proof of delivery is
        // contested, not that a model fired.
        if (delivery.getClient() != null) {
            notificationService.createNotification(delivery.getClient(),
                    "Your delivery '" + delivery.getDescription()
                            + "' is being verified. Delivery proof is under review.");
        }
    }

    public com.delivery.backend.modules.delivery.dto.LocationUpdateRequest getCourierLocation(UUID deliveryId) {
        String json;
        try {
            json = redisTemplate.opsForValue().get("livreur:position:" + deliveryId);
        } catch (Exception e) {
            // Redis holds the live marker, never the order itself. A read failure
            // must not take down a list that is otherwise perfectly readable:
            // otherwise a single IN_TRANSIT delivery turns every listing that
            // contains it into a 500. This is the same contract as the write
            // path above -- losing a coordinate is cheaper than losing the order
            // -- and it is what keeps the platform usable when the cache is
            // unreachable (e.g. a Render Key Value provisioned in another region
            // than the backend).
            log.warn("could not read courier position for delivery {}: {}", deliveryId, e.toString());
            return null;
        }
        if (json != null) {
            try {
                return objectMapper.readValue(json, com.delivery.backend.modules.delivery.dto.LocationUpdateRequest.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Client access to a courier's position, restricted to the client who owns the
     * order. Without this check any authenticated client could follow any delivery
     * by guessing its UUID (IDOR).
     *
     * <p>A delivery that is not yours reports the same thing as a delivery that
     * does not exist, on purpose. Distinguishing the two would turn this endpoint
     * into an existence oracle: an attacker enumerating UUIDs could tell "this
     * order is real, just not mine" from "this order is not real", which is how a
     * guessable-identifier leak is turned into a map of the order book.
     */
    public com.delivery.backend.modules.delivery.dto.LocationUpdateRequest getCourierLocationForClient(
            UUID deliveryId, User client) {
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || !delivery.getClient().getId().equals(client.getId())) {
            throw new IllegalArgumentException("Delivery not found");
        }
        return getCourierLocation(deliveryId);
    }

    /**
     * What a sealed proof of delivery looks like when read back.
     *
     * @param verified     the MAC recomputed from the stored fields matches the
     *                     one on record. False means a field was altered after
     *                     the delivery, or the key was rotated since.
     * @param verifiable   whether verification was even possible. A delivery
     *                     unassigned after the fact can no longer be verified,
     *                     because the courier's identity is part of what was
     *                     sealed; reporting that as "verified" would be a lie and
     *                     as "tampered" would be a false accusation.
     */
    public record DeliveryProofView(
            boolean verified,
            boolean verifiable,
            LocalDateTime deliveredAt,
            Double deliveredLat,
            Double deliveredLng,
            Double distanceM,
            String proof) {
    }

    /**
     * Re-derives the seal for a delivery from its stored fields and reports
     * whether it still matches.
     *
     * <p>Returns null when the delivery was never sealed, so the caller can
     * answer honestly instead of reporting an unverifiable delivery as fine.
     */
    @Transactional(readOnly = true)
    public DeliveryProofView getProof(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (delivery.getProofHash() == null || delivery.getDeliveredAt() == null
                || delivery.getDeliveredLat() == null || delivery.getDeliveredLng() == null) {
            return null;
        }

        User courier = delivery.getCourier();
        boolean verifiable = courier != null && delivery.getClient() != null;
        boolean verified = verifiable && proofService.verify(
                delivery.getId(),
                courier.getId(),
                delivery.getClient().getId(),
                delivery.getDeliveredLat(),
                delivery.getDeliveredLng(),
                delivery.getDeliveredAt(),
                delivery.getProofHash());

        return new DeliveryProofView(
                verified,
                verifiable,
                delivery.getDeliveredAt(),
                delivery.getDeliveredLat(),
                delivery.getDeliveredLng(),
                delivery.getProofDistanceM(),
                delivery.getProofHash());
    }

    /**
     * Prices a delivery without persisting anything, so the client can be shown the
     * exact figure the server will charge.
     */
    public DeliveryQuoteResponse quoteDelivery(DeliveryRequest request) {
        return pricingService.quote(
                request.getPickupLat(), request.getPickupLng(),
                request.getDropoffLat(), request.getDropoffLng());
    }

    private DeliveryResponse toResponse(Delivery delivery) {
        DeliveryResponse response = new DeliveryResponse(delivery);
        if (delivery.getStatus() == DeliveryStatus.IN_TRANSIT) {
            com.delivery.backend.modules.delivery.dto.LocationUpdateRequest loc = getCourierLocation(delivery.getId());
            if (loc != null) {
                response.setCourierLatitude(loc.getLatitude());
                response.setCourierLongitude(loc.getLongitude());
            }
        }
        return response;
    }

    @Transactional
    public DeliveryResponse createDelivery(DeliveryRequest request, User client) {
        Delivery delivery = new Delivery(
                request.getDescription(),
                request.getPickupAddress(),
                request.getDropoffAddress(),
                client
        );

        // Coordinates are now mandatory. The previous implementation substituted
        // randomly generated points around Casablanca whenever they were absent, which
        // meant the stored coordinates — and therefore the stored price — had no
        // relationship to the address the client actually selected.
        delivery.setPickupLat(request.getPickupLat());
        delivery.setPickupLng(request.getPickupLng());
        delivery.setDropoffLat(request.getDropoffLat());
        delivery.setDropoffLng(request.getDropoffLng());

        // The server owns the price. Any figure supplied by the client is ignored, so
        // the amount shown at checkout and the amount recorded here cannot diverge.
        DeliveryQuoteResponse quote = pricingService.quote(
                request.getPickupLat(), request.getPickupLng(),
                request.getDropoffLat(), request.getDropoffLng());

        if (quote.isExceedsTimeLimit()) {
            throw new IllegalArgumentException(
                    "This delivery cannot be served: the road route takes about "
                            + (quote.getDurationSeconds() / 60)
                            + " minutes, beyond the " + (PricingService.MAX_TRANSIT_SECONDS / 60)
                            + " minute limit. Please choose closer locations.");
        }

        delivery.setPrice(quote.getPrice());

        // Money is NOT recognised here. Previously the order was marked PAID at creation
        // even though the checkout form is a simulation with no capture step, so the
        // revenue figure was really "every order that was never cancelled". The order
        // now starts unpaid and only becomes PAID in capturePayment().
        delivery.setPaymentStatus(PaymentStatus.PENDING_PAYMENT);

        // Note: auto-dispatch deliberately does not run here. A courier is reserved
        // only once payment has been captured, so a failed checkout cannot occupy
        // delivery capacity. This also keeps the OR-Tools/OSRM work off the
        // create-delivery request path.
        return toResponse(deliveryRepository.save(delivery));
    }

    /**
     * Captures payment for an order and, only then, hands it to the auto-dispatcher.
     */
    @Transactional
    public DeliveryResponse capturePayment(UUID deliveryId, User client) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (!delivery.getClient().getId().equals(client.getId())) {
            throw new IllegalArgumentException("You can only pay for your own deliveries");
        }

        if (!PaymentStatus.PENDING_PAYMENT.equals(delivery.getPaymentStatus())) {
            throw new IllegalArgumentException("This delivery is not awaiting payment");
        }

        delivery.setPaymentStatus(PaymentStatus.PAID);
        delivery = deliveryRepository.save(delivery);

        delivery = autoDispatch(delivery);

        return toResponse(delivery);
    }

    /**
     * DVRP-TW auto-dispatch: finds the courier whose existing workload plus this
     * delivery fits inside the 45-minute time-window constraint, and gives the
     * stop to the one whose round comes out shortest.
     *
     * <p>Extracted from createDelivery so that dispatch is tied to payment capture.
     *
     * <p>This used to stop at the <em>first</em> courier that fitted. That made
     * the platform look broken rather than simple: couriers came back in
     * insertion order, so one courier absorbed every order and the others sat
     * idle with an empty round. Two paid orders both landed on courier1 and
     * courier2 was never dispatched, ever. Scoring all candidates and taking the
     * cheapest round is the same amount of solver work and actually balances the
     * fleet. An administrator can still override the choice afterwards.
     */
    private Delivery autoDispatch(Delivery delivery) {
        try {
            List<User> allCouriers = userRepository.findAll().stream()
                .filter(u -> Role.LIVREUR.equals(u.getRole()))
                .toList();

            if (allCouriers.isEmpty()) {
                log.info("Delivery {} left unassigned: no couriers exist on the platform.", delivery.getId());
                return delivery;
            }

            User bestCourier = null;
            long bestSeconds = Long.MAX_VALUE;
            int bestStops = Integer.MAX_VALUE;

            for (User potentialCourier : allCouriers) {
                // Get this courier's active deliveries
                List<Delivery> activeDeliveries = deliveryRepository.findByCourierOrderByCreatedAtDesc(potentialCourier).stream()
                    .filter(d -> d.getStatus() == DeliveryStatus.ASSIGNED || d.getStatus() == DeliveryStatus.IN_TRANSIT)
                    .collect(java.util.stream.Collectors.toList());

                int loadBefore = activeDeliveries.size();

                // Add the new delivery to test if it fits
                activeDeliveries.add(delivery);

                // Start the round from where this courier actually is. Every
                // courier was previously assumed to sit at one fixed Casablanca
                // point, which made the routing decision independent of where
                // anyone actually was. Fall back to the city centre only when the
                // courier has never broadcast a position.
                double[] start = lastKnownPosition(potentialCourier, activeDeliveries);

                try {
                    RouteOptimizationResponse optResponse =
                        routeOptimizationService.optimizeDeliveries(start[0], start[1], activeDeliveries);

                    if (isServed(optResponse, delivery)) {
                        int stops = optResponse.getOrderedWaypoints().size();
                        // Cheapest round wins. Ties go to the less loaded courier,
                        // so a single order cannot be decided by iteration order.
                        boolean better = optResponse.getTotalTimeSeconds() < bestSeconds
                                || (optResponse.getTotalTimeSeconds() == bestSeconds && loadBefore < bestStops);
                        if (better) {
                            bestSeconds = optResponse.getTotalTimeSeconds();
                            bestStops = loadBefore;
                            bestCourier = potentialCourier;
                        }
                    } else {
                        // The solver is allowed to discard stops: each pickup and
                        // drop-off is a disjunctive (droppable) node, so an infeasible
                        // stop is dropped for a penalty rather than reported as an
                        // error. A non-empty route therefore proves nothing — the only
                        // thing that matters is whether THIS delivery survived.
                        log.debug("Courier {} cannot absorb delivery {} within the constraints.",
                                potentialCourier.getEmail(), delivery.getId());
                    }
                } catch (Exception e) {
                    log.warn("Routing failed for courier {}: {}", potentialCourier.getEmail(), e.getMessage());
                }
            }

            if (bestCourier != null) {
                delivery.setCourier(bestCourier);
                delivery.setStatus(DeliveryStatus.ASSIGNED);
                delivery = deliveryRepository.save(delivery);

                notificationService.createNotification(bestCourier, "New delivery auto-assigned to your route.");
                notificationService.createNotification(delivery.getClient(), "Courier found and auto-assigned based on optimal routing.");

                log.info("Delivery {} assigned to {} ({} active stops before, round {}s).",
                        delivery.getId(), bestCourier.getEmail(), bestStops, bestSeconds);
            } else {
                log.info("Delivery {} left unassigned: no courier can absorb it within the "
                        + "45-minute constraint.", delivery.getId());
            }
        } catch (Exception e) {
            log.error("Auto-dispatch failed for delivery {}", delivery.getId(), e);
        }

        return delivery;
    }

    /**
     * True only if the solver actually placed this delivery in the route.
     *
     * <p>This is the check that makes the 45-minute constraint mean anything.
     * Testing for a non-empty route instead is the trap: the model returns the
     * courier's other stops, the list is non-empty, and the delivery gets
     * assigned despite never having fit.
     */
    private boolean isServed(RouteOptimizationResponse response, Delivery delivery) {
        if (response == null || response.getOrderedWaypoints() == null) {
            return false;
        }
        return response.getOrderedWaypoints().stream()
                .anyMatch(w -> delivery.getId().equals(w.getDeliveryId()));
    }

    /**
     * Best available estimate of where a courier is: the freshest position
     * broadcast for any of their active deliveries, else the city centre.
     */
    private double[] lastKnownPosition(User courier, List<Delivery> activeDeliveries) {
        for (Delivery d : activeDeliveries) {
            if (d.getId() == null) continue;
            var loc = getCourierLocation(d.getId());
            if (loc != null && loc.getLatitude() != null && loc.getLongitude() != null) {
                return new double[]{loc.getLatitude(), loc.getLongitude()};
            }
        }
        return new double[]{DEFAULT_COURIER_LAT, DEFAULT_COURIER_LNG};
    }

    @Transactional
    public DeliveryResponse cancelDelivery(UUID deliveryId, User client) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (!delivery.getClient().getId().equals(client.getId())) {
            throw new IllegalArgumentException("You can only cancel your own deliveries");
        }

        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            throw new IllegalArgumentException("You can only cancel a delivery while it is PENDING");
        }

        delivery.setStatus(DeliveryStatus.CANCELLED);

        // Distinguish "we took the money back" from "there was never any money".
        // Cancelling an unpaid order voids it; cancelling a paid one refunds it.
        if (PaymentStatus.PAID.equals(delivery.getPaymentStatus())) {
            delivery.setPaymentStatus(PaymentStatus.REFUNDED);
        } else if (PaymentStatus.PENDING_PAYMENT.equals(delivery.getPaymentStatus())) {
            delivery.setPaymentStatus(PaymentStatus.VOIDED);
        }
        delivery = deliveryRepository.save(delivery);
        
        if (delivery.getCourier() != null) {
            notificationService.createNotification(delivery.getCourier(), 
                "Delivery '" + delivery.getDescription() + "' has been cancelled by the client.");
        }
        
        return toResponse(delivery);
    }

    /**
     * Manual assignment, admin only.
     *
     * <p>Works on a delivery in any state, including one that is already
     * assigned or even already delivered: reassigning puts it back to
     * {@code ASSIGNED} so the new courier has a stop to work. That reset is
     * deliberate, and it is what lets a dispatcher move a parcel between
     * couriers mid-round.
     *
     * <p>The courier who loses the stop is told, not just the one who gains it.
     * Silently dropping a delivery out of someone's round is the kind of thing
     * that makes a courier distrust the dashboard.
     */
    @Transactional
    public DeliveryResponse assignCourier(UUID deliveryId, UUID courierId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        User courier = userRepository.findById(courierId)
                .orElseThrow(() -> new IllegalArgumentException("Courier not found"));

        if (courier.getRole() != Role.LIVREUR) {
            throw new IllegalArgumentException("User is not a courier");
        }

        User previousCourier = delivery.getCourier();
        if (previousCourier != null && previousCourier.getId().equals(courier.getId())) {
            throw new IllegalArgumentException(
                    "'" + courier.getEmail() + "' is already assigned to this delivery");
        }

        log.warn("Admin assigned delivery {} from {} to {} (was {}).", deliveryId,
                previousCourier != null ? previousCourier.getEmail() : "nobody",
                courier.getEmail(), delivery.getStatus());

        delivery.setCourier(courier);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery = deliveryRepository.save(delivery);

        if (previousCourier != null) {
            notificationService.createNotification(previousCourier,
                "Delivery '" + delivery.getDescription() + "' has been reassigned to another courier.");
        }
        notificationService.createNotification(courier,
            "You have been assigned a new delivery: " + delivery.getDescription());

        notificationService.createNotification(delivery.getClient(),
            "Your delivery '" + delivery.getDescription() + "' has been assigned to a courier.");

        return toResponse(delivery);
    }

    /**
     * Drops the courier from a delivery, admin only.
     *
     * <p>Needed to put an order back into the unassigned pool: the automatic
     * dispatcher only ever runs at payment capture, so without this there is no
     * way to return a wrongly-assigned parcel to the queue for a fresh decision.
     * The delivery goes back to {@code PENDING} — leaving it {@code ASSIGNED}
     * with no courier would be a state the rest of the app has to defend against.
     */
    @Transactional
    public DeliveryResponse unassignCourier(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (delivery.getCourier() == null) {
            throw new IllegalArgumentException("This delivery has no courier to remove");
        }

        User previousCourier = delivery.getCourier();
        log.warn("Admin unassigned delivery {} from {} (was {}).",
                deliveryId, previousCourier.getEmail(), delivery.getStatus());

        delivery.setCourier(null);
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery = deliveryRepository.save(delivery);

        notificationService.createNotification(previousCourier,
            "Delivery '" + delivery.getDescription() + "' is no longer assigned to you.");
        notificationService.createNotification(delivery.getClient(),
            "Your delivery '" + delivery.getDescription() + "' is being reassigned.");

        return toResponse(delivery);
    }

    @Transactional
    public DeliveryResponse updateDeliveryStatus(UUID deliveryId, DeliveryStatus newStatus, User courier) {
        return updateDeliveryStatus(deliveryId, newStatus, null, null, courier);
    }

    /**
     * Moves a delivery to a new status, sealing a proof of delivery when — and
     * only when — the target is DELIVERED.
     *
     * <p>Reaching DELIVERED requires a fresh GPS position, either supplied in the
     * request or taken from the courier's last broadcast (Redis). If neither
     * exists the delivery is refused, and if the position is further than
     * {@link DeliveryProofService#MAX_DELIVERY_DISTANCE_M} from the destination it
     * is refused as well: a delivery cannot be confirmed from the other side of
     * the city. What is sealed, and why it is an HMAC rather than a hash, is
     * documented on {@link DeliveryProofService}.
     */
    @Transactional
    public DeliveryResponse updateDeliveryStatus(UUID deliveryId, DeliveryStatus newStatus,
                                                 Double proofLat, Double proofLng, User courier) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (delivery.getCourier() == null || !delivery.getCourier().getId().equals(courier.getId())) {
            throw new IllegalArgumentException("You are not assigned to this delivery");
        }

        if (newStatus == DeliveryStatus.DELIVERED) {
            sealDeliveryProof(delivery, proofLat, proofLng, courier);
        }

        delivery.setStatus(newStatus);
        delivery = deliveryRepository.save(delivery);
        
        notificationService.createNotification(delivery.getClient(), 
            "The status of your delivery '" + delivery.getDescription() + "' has changed to " + newStatus);
            
        return toResponse(delivery);
    }

    /**
     * Status override, admin only. No courier-ownership check: an administrator
     * may move any delivery to any state (e.g. force DELIVERED, reopen as
     * ASSIGNED, cancel an in-flight order). Both parties are notified so the
     * override never happens silently.
     */
    @Transactional
    public DeliveryResponse adminUpdateStatus(UUID deliveryId, DeliveryStatus newStatus) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        log.warn("Admin overrode delivery {} status: {} -> {}.",
                deliveryId, delivery.getStatus(), newStatus);
        delivery.setStatus(newStatus);
        delivery = deliveryRepository.save(delivery);

        notificationService.createNotification(delivery.getClient(),
            "An administrator changed the status of your delivery '"
                + delivery.getDescription() + "' to " + newStatus);
        if (delivery.getCourier() != null) {
            notificationService.createNotification(delivery.getCourier(),
                "An administrator changed the status of delivery '"
                    + delivery.getDescription() + "' to " + newStatus);
        }

        return toResponse(delivery);
    }

    private void sealDeliveryProof(Delivery delivery, Double proofLat, Double proofLng, User courier) {
        Double lat = proofLat;
        Double lng = proofLng;

        if (lat == null || lng == null) {
            var lastKnown = getCourierLocation(delivery.getId());
            if (lastKnown != null) {
                lat = lastKnown.getLatitude();
                lng = lastKnown.getLongitude();
            }
        }

        if (lat == null || lng == null) {
            throw new IllegalArgumentException(
                    "Live GPS position required to confirm delivery: broadcast your location first");
        }

        Double distanceM = null;
        if (delivery.getDropoffLat() != null && delivery.getDropoffLng() != null) {
            distanceM = proofService.haversineMeters(lat, lng,
                    delivery.getDropoffLat(), delivery.getDropoffLng());
            if (distanceM > DeliveryProofService.MAX_DELIVERY_DISTANCE_M) {
                throw new IllegalArgumentException(
                        "Delivery refused: you are " + Math.round(distanceM)
                                + " m from the destination (limit "
                                + Math.round(DeliveryProofService.MAX_DELIVERY_DISTANCE_M) + " m)");
            }
        }

        // seal() hands back the timestamp it actually hashed, and that is the one
        // persisted. Storing a fresh now() instead would look identical here and
        // break later: the in-memory clock carries nanoseconds, the column stores
        // microseconds, so re-deriving from the stored row would recompute a
        // different payload and report an honest delivery as tampered with.
        DeliveryProofService.Seal seal = proofService.seal(
                delivery.getId(), courier.getId(), delivery.getClient().getId(), lat, lng,
                LocalDateTime.now());

        delivery.setDeliveredLat(lat);
        delivery.setDeliveredLng(lng);
        delivery.setDeliveredAt(seal.sealedAt());
        delivery.setProofDistanceM(distanceM);
        delivery.setProofHash(seal.hash());
        log.info("Delivery {} sealed: proof {} at {} m from destination.",
                delivery.getId(), seal.hash().substring(0, 12),
                distanceM == null ? -1 : Math.round(distanceM));
    }

    public List<DeliveryResponse> getDeliveriesForClient(User client) {
        return deliveryRepository.findByClientOrderByCreatedAtDesc(client).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<DeliveryResponse> getDeliveriesForCourier(User courier) {
        return deliveryRepository.findByCourierOrderByCreatedAtDesc(courier).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public User getUserById(UUID userId) {
        return userRepository.findById(userId).orElse(null);
    }

    public List<Delivery> getRawDeliveriesForCourier(User courier) {
        return deliveryRepository.findByCourierOrderByCreatedAtDesc(courier);
    }

    public List<DeliveryResponse> getAllDeliveries() {
        return deliveryRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Hard delete, admin only. Unlike the client-facing deletion, no status
     * restriction applies: an administrator may remove a delivery in any state
     * (PENDING, ASSIGNED, IN_TRANSIT included). Callers are expected to have
     * verified the ADMIN role — this controller mapping is admin-only.
     */
    @Transactional
    public void deleteDelivery(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        log.warn("Admin force-deleted delivery {} (was {}, {}).",
                deliveryId, delivery.getStatus(), delivery.getPaymentStatus());
        deliveryRepository.delete(delivery);
    }

    @Transactional
    public void deleteMyDelivery(UUID deliveryId, User client) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (!delivery.getClient().getId().equals(client.getId())) {
            throw new IllegalArgumentException("You can only delete your own deliveries");
        }

        if (delivery.getStatus() != DeliveryStatus.DELIVERED && delivery.getStatus() != DeliveryStatus.CANCELLED) {
            throw new IllegalArgumentException("Only delivered or cancelled deliveries can be deleted");
        }

        deliveryRepository.delete(delivery);
    }

    @Transactional
    public DeliveryResponse rateDelivery(UUID deliveryId, int rating, String reviewComment, User client) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (!delivery.getClient().getId().equals(client.getId())) {
            throw new IllegalArgumentException("You can only rate your own deliveries");
        }

        if (delivery.getStatus() != DeliveryStatus.DELIVERED) {
            throw new IllegalArgumentException("You can only rate delivered deliveries");
        }

        if (delivery.getRating() != null) {
            throw new IllegalArgumentException("Delivery is already rated");
        }

        delivery.setRating(rating);
        delivery.setReviewComment(reviewComment);
        delivery = deliveryRepository.save(delivery);

        return toResponse(delivery);
    }

    /**
     * A courier's public track record.
     *
     * <p>Derived entirely from the deliveries the courier was assigned, because
     * there is no separate courier profile to hold it. {@code averageRating} stays
     * null when nothing has been rated yet, so the UI can distinguish "no ratings"
     * from "rated zero" instead of printing a 0.0 that looks like a bad score.
     */
    public static class CourierStatsDTO {
        public long totalDeliveries;
        public long completedDeliveries;
        public long activeDeliveries;
        public long cancelledDeliveries;
        public long ratingCount;
        public Double averageRating;

        public CourierStatsDTO(long totalDeliveries, long completedDeliveries, long activeDeliveries,
                               long cancelledDeliveries, long ratingCount, Double averageRating) {
            this.totalDeliveries = totalDeliveries;
            this.completedDeliveries = completedDeliveries;
            this.activeDeliveries = activeDeliveries;
            this.cancelledDeliveries = cancelledDeliveries;
            this.ratingCount = ratingCount;
            this.averageRating = averageRating;
        }
    }

    @Transactional(readOnly = true)
    public CourierStatsDTO getCourierStats(User courier) {
        List<Delivery> all = deliveryRepository.findByCourierOrderByCreatedAtDesc(courier);

        long completed = all.stream().filter(d -> d.getStatus() == DeliveryStatus.DELIVERED).count();
        long active = all.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.ASSIGNED || d.getStatus() == DeliveryStatus.IN_TRANSIT)
                .count();
        long cancelled = all.stream().filter(d -> d.getStatus() == DeliveryStatus.CANCELLED).count();

        // Average over the rated deliveries ONLY. Mapping every delivery to
        // `rating != null ? rating : 0` and dividing by all of them silently
        // dragged unrated orders in as zero-star reviews: a courier with one
        // 5-star review and two unrated deliveries was shown 1.7 instead of 5.0.
        List<Delivery> rated = all.stream().filter(d -> d.getRating() != null).toList();
        Double avg = rated.isEmpty() ? null
                : rated.stream().mapToInt(Delivery::getRating).average().orElse(0.0);

        return new CourierStatsDTO(all.size(), completed, active, cancelled, rated.size(), avg);
    }

    public static class AdminStatsDTO {
        public long totalUsers;
        public long totalDeliveries;
        public long completedToday;
        public Double averageRating;
        public long activeDeliveries;
        public Double totalRevenue;

        public AdminStatsDTO(long totalUsers, long totalDeliveries, long completedToday, Double averageRating, long activeDeliveries, Double totalRevenue) {
            this.totalUsers = totalUsers;
            this.totalDeliveries = totalDeliveries;
            this.completedToday = completedToday;
            this.averageRating = averageRating;
            this.activeDeliveries = activeDeliveries;
            this.totalRevenue = totalRevenue;
        }
    }

    public AdminStatsDTO getAdminStats() {
        long users = userRepository.count();
        long total = deliveryRepository.count();
        long completedToday = deliveryRepository.countByStatusAndUpdatedAtAfter(
                DeliveryStatus.DELIVERED, LocalDateTime.now().with(LocalTime.MIN));
        long active = deliveryRepository.countByStatus(DeliveryStatus.IN_TRANSIT) + 
                      deliveryRepository.countByStatus(DeliveryStatus.ASSIGNED);
        Double avgRating = deliveryRepository.getAverageRating();
        if (avgRating == null) avgRating = 0.0;
        
        Double totalRevenue = deliveryRepository.getTotalRevenue();
        if (totalRevenue == null) totalRevenue = 0.0;
        
        // Round to 1 decimal place
        avgRating = Math.round(avgRating * 10.0) / 10.0;

        return new AdminStatsDTO(users, total, completedToday, avgRating, active, totalRevenue);
    }

    public static class AdminActivityDTO {
        public String type;
        public String text;
        public LocalDateTime timestamp;

        public AdminActivityDTO(String type, String text, LocalDateTime timestamp) {
            this.type = type;
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    public List<AdminActivityDTO> getAdminActivities() {
        List<AdminActivityDTO> activities = new ArrayList<>();

        // 1. Get recent users
        List<User> recentUsers = userRepository.findTop5ByOrderByCreatedAtDesc();
        for (User u : recentUsers) {
            activities.add(new AdminActivityDTO("USER_REGISTERED", "New user registration: " + u.getEmail(), u.getCreatedAt()));
        }

        // 2. Get recent deliveries
        List<Delivery> recentDeliveries = deliveryRepository.findTop10ByOrderByUpdatedAtDesc();
        for (Delivery d : recentDeliveries) {
            if (d.getStatus() == DeliveryStatus.DELIVERED) {
                activities.add(new AdminActivityDTO("DELIVERY_DELIVERED", "Delivery '" + d.getDescription() + "' marked as completed", d.getUpdatedAt()));
                if (d.getRating() != null) {
                    activities.add(new AdminActivityDTO("DELIVERY_RATED", "Delivery '" + d.getDescription() + "' rated " + d.getRating() + " stars", d.getUpdatedAt()));
                }
            } else if (d.getStatus() == DeliveryStatus.ASSIGNED) {
                String courierEmail = d.getCourier() != null ? d.getCourier().getEmail() : "a courier";
                activities.add(new AdminActivityDTO("DELIVERY_ASSIGNED", "Delivery '" + d.getDescription() + "' assigned to " + courierEmail, d.getUpdatedAt()));
            } else if (d.getStatus() == DeliveryStatus.IN_TRANSIT) {
                activities.add(new AdminActivityDTO("DELIVERY_IN_TRANSIT", "Delivery '" + d.getDescription() + "' is out for delivery", d.getUpdatedAt()));
            } else if (d.getStatus() == DeliveryStatus.CANCELLED) {
                activities.add(new AdminActivityDTO("DELIVERY_CANCELLED", "Delivery '" + d.getDescription() + "' was cancelled", d.getUpdatedAt()));
            } else if (d.getStatus() == DeliveryStatus.PENDING) {
                activities.add(new AdminActivityDTO("DELIVERY_CREATED", "New delivery request '" + d.getDescription() + "' created", d.getCreatedAt()));
            }
        }

        // Sort by timestamp descending
        activities.sort(Comparator.comparing((AdminActivityDTO a) -> a.timestamp).reversed());

        // Return top 10
        return activities.stream().limit(10).collect(Collectors.toList());
    }
}
