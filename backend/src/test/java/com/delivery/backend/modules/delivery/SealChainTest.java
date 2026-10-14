package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.delivery.DeliveryProofService.ChainVerification;
import com.delivery.backend.modules.delivery.DeliveryProofService.ChainedSeal;
import com.delivery.backend.modules.delivery.DeliveryProofService.Seal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seal chain, tested without a database.
 *
 * <p>A per-seal HMAC proves each delivery happened as recorded; the chain proves
 * the log itself is intact. Each test below breaks the log exactly one way —
 * a tampered field, a deleted row, a reorder, a forged start, a wiped link —
 * and asserts {@code verifyChain} points at the first row that no longer
 * follows rather than just answering "broken".
 */
class SealChainTest {

    private final DeliveryProofService proofs =
            new DeliveryProofService("test-proof-root-secret-for-the-seal-chain-0123456789");

    private static final UUID COURIER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CLIENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID D1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID D2 = UUID.fromString("11111111-1111-1111-1111-111111111112");
    private static final UUID D3 = UUID.fromString("11111111-1111-1111-1111-111111111113");

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 3, 1, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 3, 1, 11, 0, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 3, 1, 12, 0, 0);

    private static final double LAT = 33.5731;
    private static final double LNG = -7.5898;

    /** Three genuinely sealed, correctly linked rows. */
    private List<ChainedSeal> chainOfThree() {
        Seal s1 = proofs.sealChained(D1, COURIER, CLIENT, LAT, LNG, T1, null);
        ChainedSeal r1 = row(D1, T1, DeliveryProofService.CHAIN_GENESIS, s1.hash());
        Seal s2 = proofs.sealChained(D2, COURIER, CLIENT, LAT, LNG, T2, s1.hash());
        ChainedSeal r2 = row(D2, T2, s1.hash(), s2.hash());
        Seal s3 = proofs.sealChained(D3, COURIER, CLIENT, LAT, LNG, T3, s2.hash());
        ChainedSeal r3 = row(D3, T3, s2.hash(), s3.hash());
        return new ArrayList<>(List.of(r1, r2, r3));
    }

    private static ChainedSeal row(UUID delivery, LocalDateTime at, String prev, String seal) {
        return new ChainedSeal(delivery, COURIER, CLIENT, LAT, LNG, at, prev, seal);
    }

    @Test
    @DisplayName("the link is bound into the MAC: a relinked seal does not verify")
    void theLinkIsBoundIntoTheMac() {
        Seal seal = proofs.sealChained(D1, COURIER, CLIENT, LAT, LNG, T1, "aaaa");
        assertTrue(proofs.verifyChained(D1, COURIER, CLIENT, LAT, LNG, T1, "aaaa", seal.hash()));

        // Editing only the stored prev_hash column — the cheapest attack on the
        // log — invalidates the seal, because the link is inside the MAC.
        assertFalse(proofs.verifyChained(D1, COURIER, CLIENT, LAT, LNG, T1, "aaab", seal.hash()),
                "the predecessor hash must be covered by the seal, not just stored beside it");

        // And a v2 seal never verifies as v1: the old verifier rejects new seals
        // instead of silently ignoring their link.
        assertFalse(proofs.verify(D1, COURIER, CLIENT, LAT, LNG, T1, seal.hash()));
    }

    @Test
    @DisplayName("a null predecessor normalises to GENESIS, so there is one first-seal spelling")
    void genesisNormalisesNull() {
        assertEquals(
                proofs.sealChained(D1, COURIER, CLIENT, LAT, LNG, T1, null).hash(),
                proofs.sealChained(D1, COURIER, CLIENT, LAT, LNG, T1,
                        DeliveryProofService.CHAIN_GENESIS).hash());
    }

    @Test
    @DisplayName("matchesSeal accepts either payload version but nothing else")
    void matchesSealAcceptsBothVersions() {
        Seal v1 = proofs.seal(D1, COURIER, CLIENT, LAT, LNG, T1);
        assertTrue(proofs.matchesSeal(D1, COURIER, CLIENT, LAT, LNG, T1, null, v1.hash()),
                "pre-chain seals keep verifying");

        Seal v2 = proofs.sealChained(D1, COURIER, CLIENT, LAT, LNG, T1, "prev");
        assertTrue(proofs.matchesSeal(D1, COURIER, CLIENT, LAT, LNG, T1, "prev", v2.hash()));
        assertFalse(proofs.matchesSeal(D1, COURIER, CLIENT, LAT, LNG, T1, "other", v2.hash()));
        assertFalse(proofs.matchesSeal(D1, COURIER, CLIENT, LAT, LNG, T1, "prev", "0".repeat(64)));
        assertFalse(proofs.matchesSeal(D1, COURIER, CLIENT, LAT, LNG, T1, "prev", null));
        assertFalse(proofs.matchesSeal(D1, null, CLIENT, LAT, LNG, T1, "prev", v2.hash()),
                "a missing identity never matches instead of throwing");
    }

    @Test
    @DisplayName("an empty log verifies: there is nothing to disagree with")
    void anEmptyLogVerifies() {
        ChainVerification result = proofs.verifyChain(List.of());
        assertTrue(result.ok());
        assertEquals(0, result.sealed());
        assertNull(result.brokenAt());
    }

    @Test
    @DisplayName("an intact chain verifies and counts its seals")
    void anIntactChainVerifies() {
        ChainVerification result = proofs.verifyChain(chainOfThree());
        assertTrue(result.ok());
        assertEquals(3, result.sealed());
        assertNull(result.brokenAt());
    }

    @Test
    @DisplayName("a tampered field breaks the chain at that row")
    void aTamperedFieldBreaksAtThatRow() {
        List<ChainedSeal> rows = chainOfThree();
        ChainedSeal tampered = rows.get(1);
        rows.set(1, new ChainedSeal(tampered.deliveryId(), COURIER, CLIENT,
                LAT + 0.5, LNG, tampered.timestamp(), tampered.prevHash(), tampered.seal()));

        ChainVerification result = proofs.verifyChain(rows);
        assertFalse(result.ok());
        assertEquals(D2, result.brokenAt());
    }

    @Test
    @DisplayName("a deleted row breaks the chain at its successor")
    void aDeletedRowBreaksAtItsSuccessor() {
        List<ChainedSeal> rows = chainOfThree();
        rows.remove(1); // the middle delivery disappears from the log

        ChainVerification result = proofs.verifyChain(rows);
        assertFalse(result.ok(), "deletion is the attack per-seal HMACs cannot see");
        assertEquals(D3, result.brokenAt(),
                "the successor's link still points at the missing seal");
    }

    @Test
    @DisplayName("reordering breaks the chain at the first misplaced row")
    void reorderingBreaksTheChain() {
        List<ChainedSeal> rows = chainOfThree();
        ChainedSeal second = rows.remove(1);
        rows.add(second);

        ChainVerification result = proofs.verifyChain(rows);
        assertFalse(result.ok());
        assertEquals(D3, result.brokenAt());
    }

    @Test
    @DisplayName("a forged start breaks the chain at the first row")
    void aForgedStartBreaksAtTheFirstRow() {
        List<ChainedSeal> rows = chainOfThree();
        ChainedSeal first = rows.get(0);
        rows.set(0, new ChainedSeal(first.deliveryId(), COURIER, CLIENT,
                LAT, LNG, first.timestamp(), "forged-prev", first.seal()));

        ChainVerification result = proofs.verifyChain(rows);
        assertFalse(result.ok());
        assertEquals(D1, result.brokenAt());
    }

    @Test
    @DisplayName("a wiped link breaks the chain at that row")
    void aWipedLinkBreaksTheChain() {
        List<ChainedSeal> rows = chainOfThree();
        ChainedSeal second = rows.get(1);
        rows.set(1, new ChainedSeal(second.deliveryId(), COURIER, CLIENT,
                LAT, LNG, second.timestamp(), null, second.seal()));

        ChainVerification result = proofs.verifyChain(rows);
        assertFalse(result.ok(), "only the very first row may carry no link");
        assertEquals(D2, result.brokenAt());
    }

    @Test
    @DisplayName("a row unassigned after sealing is link-only, like getProof's verifiable flag")
    void anUnassignedRowIsLinkOnly() {
        List<ChainedSeal> rows = chainOfThree();
        ChainedSeal second = rows.get(1);
        // The admin removed the courier after sealing: no identity left to
        // re-derive the MAC from, but seal and link are untouched, so the chain
        // holds on linkage alone.
        rows.set(1, new ChainedSeal(second.deliveryId(), null, null,
                LAT, LNG, second.timestamp(), second.prevHash(), second.seal()));

        assertTrue(proofs.verifyChain(rows).ok());

        // And the link on such a row is still enforced.
        rows.set(1, new ChainedSeal(second.deliveryId(), null, null,
                LAT, LNG, second.timestamp(), "elsewhere", second.seal()));
        ChainVerification result = proofs.verifyChain(rows);
        assertFalse(result.ok());
        assertEquals(D2, result.brokenAt());
    }
}
