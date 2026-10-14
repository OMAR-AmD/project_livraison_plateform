package com.delivery.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The boot-time secret guard, tested without a Spring context: it is a plain
 * check on a string, and the {@code @Value} above only decides where the
 * string comes from.
 */
class RequiredSecretsGuardTest {

    @Test
    @DisplayName("a missing or blank database password refuses to boot")
    void blankIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> new RequiredSecretsGuard(null).checkSecrets());
        assertThrows(IllegalStateException.class,
                () -> new RequiredSecretsGuard("").checkSecrets());
        assertThrows(IllegalStateException.class,
                () -> new RequiredSecretsGuard("   ").checkSecrets(),
                "whitespace is not a password either");
    }

    @Test
    @DisplayName("any set value passes: strength is the deployer's business")
    void setPasses() {
        assertDoesNotThrow(() -> new RequiredSecretsGuard("anything").checkSecrets());
    }
}
