package com.delivery.backend.modules.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    
    // Spring magically writes the SQL for this based on the method name!
    // SELECT * FROM users WHERE email = ?
    Optional<User> findByEmail(String email);
    List<User> findByRole(Role role);
    Optional<User> findByVerificationToken(String verificationToken);
    List<User> findTop5ByOrderByCreatedAtDesc();
}