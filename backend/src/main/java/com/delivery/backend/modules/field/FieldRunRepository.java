package com.delivery.backend.modules.field;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldRunRepository extends JpaRepository<FieldRun, UUID> {

    Optional<FieldRun> findByDeliveryId(UUID deliveryId);

    @Query("select r.id from FieldRun r where r.retentionUntil < :cutoff")
    List<UUID> findIdsByRetentionUntilBefore(@Param("cutoff") LocalDateTime cutoff);

    int deleteByRetentionUntilBefore(LocalDateTime cutoff);
}
