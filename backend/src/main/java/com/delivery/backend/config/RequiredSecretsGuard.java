package com.delivery.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot when a required secret is blank, naming the variable.
 *
 * <p>application.yml carries no default for the database password on purpose,
 * so an unset {@code DB_PASSWORD} already fails placeholder resolution. But an
 * explicitly <em>empty</em> one resolves fine and only explodes later as a
 * Postgres authentication failure, which reads like a database problem. This
 * closes that gap the same way {@code JwtService.checkSecretKey} does for the
 * signing key: a platform that cannot authenticate to its own database should
 * refuse to start rather than run and mislead.
 */
@Component
class RequiredSecretsGuard {

    private final String dbPassword;

    RequiredSecretsGuard(@Value("${spring.datasource.password:}") String dbPassword) {
        this.dbPassword = dbPassword;
    }

    @PostConstruct
    void checkSecrets() {
        if (dbPassword == null || dbPassword.isBlank()) {
            throw new IllegalStateException(
                    "DB_PASSWORD is not set: export it (docker compose reads it from .env) "
                            + "with the same value the Postgres container uses. There is "
                            + "deliberately no default: a committed database password is a "
                            + "hardcoded secret.");
        }
    }
}
