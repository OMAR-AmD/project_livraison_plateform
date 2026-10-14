package com.delivery.backend.modules.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The seal chain's single head pointer.
 *
 * <p>Why a row instead of just reading the latest seal: two deliveries sealed
 * at the same instant both read the same head and both claim it as their
 * predecessor, forking the chain — and the fork then reports as a break on
 * honest data. Locking this one row ({@code SELECT ... FOR UPDATE}) while
 * sealing serialises sealers across every backend instance: the second sealer
 * waits, then reads the first sealer's seal as the new head. A unique
 * constraint on {@code prev_hash} would also catch the fork, but only by
 * failing one of the two deliveries; the lock lets both succeed, in order.
 *
 * <p>It also carries the monotonic seal sequence. Wall-clock timestamps tie —
 * two seals can share a microsecond — and a tie ordered arbitrarily can read
 * as a reorder. The sequence never ties, so chain order is exact. December's
 * chain-head anchor will anchor exactly this row.
 */
@Entity
@Table(name = "seal_chain_head")
public class SealChainHead {

    /** Fixed id: there is exactly one head row, and its id is 1. */
    @Id
    private Long id;

    /** The latest seal's hash, or null when nothing has been sealed yet. */
    @Column(name = "head_hash", length = 64)
    private String headHash;

    /** How many seals have been issued; 0 when the chain is empty. */
    @Column(name = "last_seq")
    private long lastSeq;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getHeadHash() { return headHash; }
    public void setHeadHash(String headHash) { this.headHash = headHash; }
    public long getLastSeq() { return lastSeq; }
    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }
}
