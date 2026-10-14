package com.delivery.backend.modules.ai;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending cancellation confirmations, one per client.
 *
 * <p>Why this exists: the 3B chat model proved it will cancel an order the
 * customer never named — "annule-la" with two live orders cancelled one of
 * them outright (see {@code frontend/verify_chat_discipline.js}). The model's
 * prompt already says to ask first, but a prompt is advice, and advice is what
 * a small model ignores. So cancelling takes two tool calls: the model
 * <em>proposes</em> (no mutation), tells the customer which order it is about
 * to cancel, and only a second call — after an explicit "yes" — executes.
 * Skipping straight to confirm, or confirming the wrong order, is refused.
 *
 * <p>One proposal per client, ten minutes to live, consumed on use. Kept in
 * memory rather than Redis on purpose: this is UX, not the security boundary
 * (ownership and PENDING-only are still enforced at execution). Losing a
 * proposal — restart, or "yes" landing on another instance — costs one
 * repeated question, never a wrongful cancellation.
 */
@Component
class CancelProposalStore {

    /** Long enough to say yes, short enough to go stale safely. */
    private static final Duration TIME_TO_LIVE = Duration.ofMinutes(10);

    private record Proposal(UUID deliveryId, Instant expiresAt) {
        boolean liveAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private final Clock clock;
    private final ConcurrentHashMap<UUID, Proposal> pending = new ConcurrentHashMap<>();

    CancelProposalStore() {
        this(Clock.systemUTC());
    }

    CancelProposalStore(Clock clock) {
        this.clock = clock;
    }

    /** Records the customer's intent to cancel; replaces any previous one. */
    void propose(UUID clientId, UUID deliveryId) {
        pending.put(clientId, new Proposal(deliveryId, clock.instant().plus(TIME_TO_LIVE)));
    }

    /**
     * Confirms and consumes the pending proposal when it names this delivery.
     * Anything else — none, expired, or a different order — leaves the store
     * untouched and answers empty, so a confused model cannot spend someone's
     * intent on the wrong order.
     */
    Optional<UUID> consume(UUID clientId, UUID deliveryId) {
        Proposal proposal = pending.get(clientId);
        if (proposal == null || !proposal.liveAt(clock.instant())) {
            pending.remove(clientId);
            return Optional.empty();
        }
        if (!proposal.deliveryId().equals(deliveryId)) {
            return Optional.empty();
        }
        pending.remove(clientId);
        return Optional.of(deliveryId);
    }

    /**
     * Confirms whatever is pending, for the "yes" that names no order. The chat
     * API is stateless — the second request carries only "oui", not the ID from
     * the first turn — and there is at most one proposal per client, so "the
     * pending one" is unambiguous. Consumes on success like {@link #consume}.
     */
    Optional<UUID> consumePending(UUID clientId) {
        Proposal proposal = pending.get(clientId);
        if (proposal == null || !proposal.liveAt(clock.instant())) {
            pending.remove(clientId);
            return Optional.empty();
        }
        pending.remove(clientId);
        return Optional.of(proposal.deliveryId());
    }

    /** For tests: how many proposals are currently held. */
    int pendingCount() {
        return pending.size();
    }
}
