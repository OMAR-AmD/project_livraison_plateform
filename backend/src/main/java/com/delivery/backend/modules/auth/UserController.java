package com.delivery.backend.modules.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(buildUserProfile(user));
    }

    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @AuthenticationPrincipal User user,
            @RequestBody UserProfileUpdateRequest request) {
        
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setDefaultAddress(request.getDefaultAddress());
        user.setAvatarUrl(request.getAvatarUrl());

        User updatedUser = userRepository.save(user);

        return ResponseEntity.ok(buildUserProfile(updatedUser));
    }

    private Map<String, Object> buildUserProfile(User user) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId().toString());
        profile.put("email", user.getEmail());
        profile.put("role", user.getRole().name());
        profile.put("firstName", user.getFirstName());
        profile.put("lastName", user.getLastName());
        profile.put("phoneNumber", user.getPhoneNumber());
        profile.put("defaultAddress", user.getDefaultAddress());
        profile.put("avatarUrl", user.getAvatarUrl());
        return profile;
    }
}
