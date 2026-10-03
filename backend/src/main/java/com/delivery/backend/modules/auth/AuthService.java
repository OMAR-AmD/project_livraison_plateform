package com.delivery.backend.modules.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final ResendEmailService resendEmailService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager,
            ResendEmailService resendEmailService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.resendEmailService = resendEmailService;
    }

    @Transactional
    public String register(RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }

        Role userRole;
        try {
            userRole = Role.valueOf(request.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid role provided");
        }

        // The requested role is client-controlled, so this guard is the only thing
        // standing between a public endpoint and full platform takeover: without it,
        // anyone can POST {"email":"...","password":"...","role":"ADMIN"} to
        // /api/v1/auth/register and authenticate as an administrator.
        // Self-service signup is limited to CLIENT and LIVREUR; promotion to ADMIN
        // is deliberately an out-of-band operation (see README).
        if (userRole == Role.ADMIN) {
            throw new IllegalArgumentException("Cannot register as ADMIN");
        }

        User user = new User(
            request.getEmail(),
            passwordEncoder.encode(request.getPassword()),
            userRole
        );
        
        // Setup Verification Token
        String token = UUID.randomUUID().toString();
        user.setVerificationToken(token);
        user.setTokenExpiresAt(LocalDateTime.now().plusHours(24));
        user.setEmailVerified(true);

        userRepository.save(user);
        
        // Trigger Email
        resendEmailService.sendVerificationEmail(user.getEmail(), token);
        
        // Log the link for easy local testing without a real Resend API key!
        System.out.println("==========================================================");
        System.out.println("TESTING VERIFICATION LINK: http://localhost:8080/api/v1/auth/verify?token=" + token);
        System.out.println("==========================================================");

        return "Registration successful. Please check your email to verify your account.";
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                request.getEmail(),
                request.getPassword()
            )
        );

        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
            
        // if (!user.getEmailVerified()) {
        //     throw new IllegalStateException("Please verify your email before logging in.");
        // }
        
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, user.getEmail(), user.getRole().name());
    }

    /**
     * Revokes every token issued to this account.
     *
     * <p>Logout could be entirely a browser-side act -- delete the token from
     * storage -- but then a copy that was already captured keeps working until it
     * expires. Bumping the account's session generation is what makes the token
     * in the wild useless, at the cost of one row write.
     */
    @Transactional
    public void logout(User user) {
        user.bumpTokenVersion();
        userRepository.save(user);
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
            .orElseThrow(() -> new IllegalArgumentException("Invalid verification token"));

        if (user.getTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Verification token has expired");
        }

        user.setEmailVerified(true);
        user.setVerificationToken(null);
        user.setTokenExpiresAt(null);

        userRepository.save(user);
    }
}
