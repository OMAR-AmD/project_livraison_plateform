package com.delivery.backend.modules.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the signing key.
 *
 * <p>These exist because of a specific, silent defect. The key used to be run
 * through a Base64 decoder, while the project's own instructions say to
 * generate it with {@code openssl rand -hex 32}. That combination happened to
 * work: hex characters are legal Base64 and 64 is a multiple of 4, so it
 * decoded to 48 bytes and the platform was fine.
 *
 * <p>The moment anything else was supplied it broke in the least useful way
 * available -- a login returned 401 "Authentication failed", which reads as a
 * wrong password and sends whoever is debugging it to the user's credentials
 * instead of the deployment's configuration. The key format was therefore
 * untested and untestable by accident, and these three cases pin the contract
 * that the documentation actually promises.
 */
class JwtServiceTest {

    private static final UserDetails ADMIN = User.withUsername("admin@swift.com")
            .password("irrelevant-here")
            .authorities("ROLE_ADMIN")
            .build();

    private static JwtService serviceWithSecret(String secret) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKey", secret);
        ReflectionTestUtils.setField(service, "jwtExpiration", 86_400_000L);
        return service;
    }

    @Test
    @DisplayName("a key that is not valid Base64 still signs and verifies")
    void aKeyThatIsNotValidBase64StillSignsAndVerifies() {
        // The literal that broke login in CI: legal-looking, 49 characters, and
        // not a length the Base64 decoder accepts.
        String secret = "ci-placeholder-key-not-used-for-a-real-deployment";
        JwtService service = serviceWithSecret(secret);

        assertDoesNotThrow(service::checkSecretKey, "49 characters is past the 32-byte floor");

        String token = service.generateToken(ADMIN);
        assertEquals("admin@swift.com", service.extractUsername(token),
                "a token signed with a non-Base64 secret must still verify against itself");
        assertTrue(service.isTokenValid(token, ADMIN));
    }

    @Test
    @DisplayName("the documented command produces a key the service accepts")
    void theDocumentedCommandProducesAKeyTheServiceAccepts() {
        // What `openssl rand -hex 32` emits: 64 hex characters.
        String secret = "72cf5fc2b91485f845a9b3bdee87dd14cb5b6948e754452c51b1d8794157214f";
        JwtService service = serviceWithSecret(secret);

        assertDoesNotThrow(service::checkSecretKey);
        assertTrue(service.isTokenValid(service.generateToken(ADMIN), ADMIN));
    }

    @Test
    @DisplayName("a key under 256 bits is refused at startup, naming the variable")
    void aKeyUnder256BitsIsRefusedAtStartupNamingTheVariable() {
        // 31 bytes: long enough to look deliberate, one short of the floor.
        JwtService service = serviceWithSecret("0123456789abcdef0123456789abcde");

        IllegalStateException e = assertThrows(IllegalStateException.class, service::checkSecretKey);
        assertTrue(e.getMessage().contains("JWT_SECRET_KEY"),
                "the failure has to name the variable, not just the length: " + e.getMessage());
        assertTrue(e.getMessage().contains("openssl rand -hex 32"),
                "the failure should say how to fix it: " + e.getMessage());
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void aTokenSignedWithADifferentKeyIsRejected() {
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        JwtService sa = serviceWithSecret(a);
        JwtService sb = serviceWithSecret(b);
        sa.checkSecretKey();
        sb.checkSecretKey();
        String token = sa.generateToken(ADMIN);

        assertThrows(RuntimeException.class, () -> sb.extractUsername(token),
                "a token must not verify under a different signing key");
    }
}
