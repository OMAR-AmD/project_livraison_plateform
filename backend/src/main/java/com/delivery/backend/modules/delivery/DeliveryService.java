package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.Role;
import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.auth.UserRepository;
import com.delivery.backend.modules.delivery.dto.DeliveryQuoteResponse;
import com.delivery.backend.modules.delivery.dto.DeliveryRequest;
import com.delivery.backend.modules.delivery.dto.DeliveryResponse;
import com.delivery.backend.modules.notification.NotificationService;
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

    public DeliveryService(DeliveryRepository deliveryRepository, UserRepository userRepository, NotificationService notificationService, RedisTemplate<String, String> redisTemplate, SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper, RouteOptimizationService routeOptimizationService, PricingService pricingService) {
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.routeOptimizationService = routeOptimizationService;
        this.pricingService = pricingService;
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
    }

    public com.delivery.backend.modules.delivery.dto.LocationUpdateRequest getCourierLocation(UUID deliveryId) {
        String json = redisTemplate.opsForValue().get("livreur:position:" + deliveryId);
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
     * DVRP-TW auto-dispatch: finds the first courier whose existing workload plus this
     * delivery still fits inside the 45-minute time-window constraint.
     *
     * <p>Extracted from createDelivery so that dispatch is tied to payment capture.
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

            for (User potentialCourier : allCouriers) {
                // Get this courier's active deliveries
                List<Delivery> activeDeliveries = deliveryRepository.findByCourierOrderByCreatedAtDesc(potentialCourier).stream()
                    .filter(d -> d.getStatus() == DeliveryStatus.ASSIGNED || d.getStatus() == DeliveryStatus.IN_TRANSIT)
                    .collect(java.util.stream.Collectors.toList());

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
                        delivery.setCourier(potentialCourier);
                        delivery.setStatus(DeliveryStatus.ASSIGNED);
                        delivery = deliveryRepository.save(delivery);

                        notificationService.createNotification(potentialCourier, "New delivery auto-assigned to your route.");
                        notificationService.createNotification(delivery.getClient(), "Courier found and auto-assigned based on optimal routing.");

                        log.info("Delivery {} assigned to {} ({} stops on the round).",
                                delivery.getId(), potentialCourier.getEmail(), optResponse.getOrderedWaypoints().size());
                        break; // Stop searching once assigned
                    }

                    // The solver is allowed to discard stops: each pickup and
                    // drop-off is a disjunctive (droppable) node, so an infeasible
                    // stop is dropped for a penalty rather than reported as an
                    // error. A non-empty route therefore proves nothing — the only
                    // thing that matters is whether THIS delivery survived.
                    log.debug("Courier {} cannot absorb delivery {} within the constraints.",
                            potentialCourier.getEmail(), delivery.getId());

                } catch (Exception e) {
                    // Solver or OSRM failure for this courier: try the next one.
                    log.debug("Routing failed for courier {}: {}", potentialCourier.getEmail(), e.getMessage());
                }
            }

            if (delivery.getCourier() == null) {
                log.warn("Delivery {} left PENDING: no courier could take it within the 45-minute limit.",
                        delivery.getId());
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

    @Transactional
    public DeliveryResponse assignCourier(UUID deliveryId, UUID courierId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        User courier = userRepository.findById(courierId)
                .orElseThrow(() -> new IllegalArgumentException("Courier not found"));

        if (courier.getRole() != Role.LIVREUR) {
            throw new IllegalArgumentException("User is not a courier");
        }

        delivery.setCourier(courier);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery = deliveryRepository.save(delivery);
        
        notificationService.createNotification(courier, 
            "You have been assigned a new delivery: " + delivery.getDescription());

        notificationService.createNotification(delivery.getClient(), 
            "Your delivery '" + delivery.getDescription() + "' has been assigned to a courier.");
        
        return toResponse(delivery);
    }

    @Transactional
    public DeliveryResponse updateDeliveryStatus(UUID deliveryId, DeliveryStatus newStatus, User courier) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (delivery.getCourier() == null || !delivery.getCourier().getId().equals(courier.getId())) {
            throw new IllegalArgumentException("You are not assigned to this delivery");
        }

        delivery.setStatus(newStatus);
        delivery = deliveryRepository.save(delivery);
        
        notificationService.createNotification(delivery.getClient(), 
            "The status of your delivery '" + delivery.getDescription() + "' has changed to " + newStatus);
            
        return toResponse(delivery);
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

    @Transactional
    public void deleteDelivery(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found"));

        if (delivery.getStatus() != DeliveryStatus.DELIVERED && delivery.getStatus() != DeliveryStatus.CANCELLED) {
            throw new IllegalArgumentException("Only delivered or cancelled deliveries can be deleted");
        }

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
