package com.delivery.backend.modules.auth;

import com.delivery.backend.modules.delivery.DeliveryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserRepository userRepository;
    private final DeliveryRepository deliveryRepository;

    public AdminUserController(UserRepository userRepository, DeliveryRepository deliveryRepository) {
        this.userRepository = userRepository;
        this.deliveryRepository = deliveryRepository;
    }

    @GetMapping("/couriers")
    public ResponseEntity<List<Map<String, Object>>> getCouriers() {
        List<Map<String, Object>> couriers = userRepository.findByRole(Role.LIVREUR).stream()
                .map(user -> Map.of(
                        "id", (Object) user.getId(),
                        "email", (Object) user.getEmail()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(couriers);
    }

    /**
     * Every user, each carrying their delivery track record.
     *
     * <p>The rating a dispatcher needs to judge a courier lives on the delivery, not
     * on the user row, so it is joined in here rather than left for the UI to
     * reconstruct from the deliveries list. The aggregates come from a single
     * {@code GROUP BY courier} query and are looked up by id: doing it per user
     * would issue one query per row.
     *
     * <p>{@code averageRating} is null for users with nothing rated, which is also
     * always the case for clients and admins. The UI shows that as "no ratings"
     * rather than as a zero score.
     */
    @Transactional(readOnly = true)
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        Map<UUID, DeliveryRepository.CourierStatsProjection> stats = new HashMap<>();
        for (DeliveryRepository.CourierStatsProjection row : deliveryRepository.aggregateCourierStats()) {
            stats.put(row.getCourierId(), row);
        }

        List<Map<String, Object>> users = userRepository.findAll().stream()
                .map(user -> {
                    DeliveryRepository.CourierStatsProjection row = stats.get(user.getId());
                    Map<String, Object> u = new HashMap<>();
                    u.put("id", user.getId());
                    u.put("email", user.getEmail());
                    u.put("role", user.getRole().name());
                    u.put("verified", user.getEmailVerified());
                    u.put("totalDeliveries", row != null ? row.getTotalDeliveries() : 0L);
                    u.put("deliveredDeliveries", row != null ? row.getDeliveredDeliveries() : 0L);
                    u.put("ratingCount", row != null ? row.getRatingCount() : 0L);
                    u.put("averageRating", row != null ? row.getAverageRating() : null);
                    return u;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(users);
    }
}
