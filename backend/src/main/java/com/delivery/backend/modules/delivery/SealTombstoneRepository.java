package com.delivery.backend.modules.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SealTombstoneRepository extends JpaRepository<SealTombstone, UUID> {

    /** All recorded deletions in chain order, for merging into the walk. */
    List<SealTombstone> findAllByOrderBySealSeqAsc();
}
