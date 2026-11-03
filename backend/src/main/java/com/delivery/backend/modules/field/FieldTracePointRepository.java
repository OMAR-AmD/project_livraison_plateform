package com.delivery.backend.modules.field;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface FieldTracePointRepository extends JpaRepository<FieldTracePoint, UUID> {

    @Modifying
    @Query("delete from FieldTracePoint p where p.run.id in :runIds")
    int deleteByRunIdIn(@Param("runIds") Collection<UUID> runIds);
}
