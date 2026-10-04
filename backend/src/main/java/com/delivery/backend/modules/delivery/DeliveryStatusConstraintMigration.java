package com.delivery.backend.modules.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Reconciles the {@code deliveries} status CHECK constraint with the
 * {@link DeliveryStatus} enum on every boot.
 *
 * <p>The constraint ({@code deliveries_status_check}) was created out-of-band:
 * it exists in no migration file in this repository, so
 * {@code ddl-auto:update} neither created it nor updates it. When a new status
 * is added to the enum (e.g. ARRIVED), the database keeps refusing it and the
 * failure surfaces as a 409 "a record already exists" that points nowhere
 * useful — the write looks like a duplicate rather than a rejected value.
 *
 * <p>Drop-if-exists plus re-add keeps this idempotent across local Docker, the
 * cloud free database (which is periodically reprovisioned), and any future
 * status. If it cannot run (read-only credentials), it warns and the platform
 * still boots; writes of unknown statuses then fail loudly at the database
 * rather than silently.
 */
@Component
public class DeliveryStatusConstraintMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusConstraintMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public DeliveryStatusConstraintMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String allowed = Arrays.stream(DeliveryStatus.values())
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            jdbcTemplate.execute("ALTER TABLE deliveries DROP CONSTRAINT IF EXISTS deliveries_status_check");
            jdbcTemplate.execute("ALTER TABLE deliveries ADD CONSTRAINT deliveries_status_check "
                    + "CHECK (status IN (" + allowed + "))");
            log.info("Deliveries status CHECK reconciled: {}", allowed);
        } catch (Exception e) {
            log.warn("Could not reconcile the deliveries status CHECK ({}: {}). "
                    + "Writes of new statuses may fail at the database.",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
