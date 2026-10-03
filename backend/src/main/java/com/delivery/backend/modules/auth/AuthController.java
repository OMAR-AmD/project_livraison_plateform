package com.delivery.backend.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest request) {
        String responseMessage = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responseMessage);
    }

    /**
     * Sign in, but not without a cost for guessing.
     *
     * <p>The rate limit is checked before the password reaches bcrypt, so a
     * blocked caller cannot use this endpoint as free computation. Failures are
     * counted, a success clears the account's counter, and the client is
     * identified by the forwarding header first so that the platform's proxy is
     * not reported as every user's address.
     *
     * <p>{@code BadCredentialsException} is counted and then re-thrown, so the
     * existing 401 shape (from {@code GlobalExceptionHandler}) is preserved while
     * the attempt is still recorded.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String clientId = clientIdentifier(http);
        rateLimiter.checkAllowed(clientId, request.getEmail());

        try {
            AuthResponse response = authService.login(request);
            rateLimiter.recordSuccess(request.getEmail());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            rateLimiter.recordFailure(clientId, request.getEmail());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (AuthenticationException e) {
            rateLimiter.recordFailure(clientId, request.getEmail());
            throw e;
        } catch (IllegalArgumentException e) {
            rateLimiter.recordFailure(clientId, request.getEmail());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
        }
    }

    /**
     * Sign out for real: clears the browser's copy is the client's job, and this
     * invalidates every token the account has been issued on the server.
     *
     * <p>Requires a currently-valid token (see SecurityConfig) so an anonymous
     * caller cannot sign someone else out.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@AuthenticationPrincipal User user) {
        authService.logout(user);
        return ResponseEntity.ok(Map.of("message", "Signed out"));
    }

    private static String clientIdentifier(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // The header is a comma-separated chain; the first entry is the
            // original client, which is the one the limiter should key on. When
            // there is no proxy the header is absent and this falls back to the
            // socket address.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyEmail(@RequestParam("token") String token) {
        try {
            authService.verifyEmail(token);
            // In a real application, you might want to return an HTML page or redirect
            // back to the frontend (e.g. return ResponseEntity.status(HttpStatus.FOUND).header("Location", "http://localhost:3000/login?verified=true").build();)
            // For now, return a simple success message string.
            return ResponseEntity.ok("Email successfully verified! You can now access all features.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Verification failed: " + e.getMessage());
        }
    }
}
