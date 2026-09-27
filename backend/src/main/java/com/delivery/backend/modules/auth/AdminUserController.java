package com.delivery.backend.modules.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserRepository userRepository;

    public AdminUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
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

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        List<Map<String, Object>> users = userRepository.findAll().stream()
                .map(user -> Map.of(
                        "id", (Object) user.getId(),
                        "email", (Object) user.getEmail(),
                        "role", (Object) user.getRole().name(),
                        "verified", (Object) user.getEmailVerified()
                ))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(users);
    }
}
