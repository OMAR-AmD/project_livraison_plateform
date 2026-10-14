package com.delivery.backend.modules.delivery;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SealChainHeadRepository extends JpaRepository<SealChainHead, Long> {

    /**
     * Reads the head row and locks it until the sealing transaction commits.
     * Every sealer goes through here, so concurrent seals line up instead of
     * forking — the second sealer blocks, then sees the first sealer's seal.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SealChainHead h where h.id = 1")
    Optional<SealChainHead> lockHead();
}
