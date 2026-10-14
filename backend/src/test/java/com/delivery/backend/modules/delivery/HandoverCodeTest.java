package com.delivery.backend.modules.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handover codes, tested directly for the first time.
 *
 * <p>Until now the only coverage was live: the throttle tests mock
 * {@code DeliveryProofService}, so no JUnit test ever executed
 * {@code handoverCode} or {@code verifyHandoverCode} — a mutation run found 22
 * mutants there with no covering test at all. A six-digit code is small enough
 * to brute force only because the throttle exists; the code derivation itself
 * still has to be right, so it gets pinned here, including an independently
 * recomputed RFC 4226 vector that fails if any step of the truncation changes.
 */
class HandoverCodeTest {

    private static final String ROOT = "test-proof-root-secret-for-handover-codes-0123456789";

    private static final UUID D1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID D2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final DeliveryProofService proofs = new DeliveryProofService(ROOT);

    @Test
    @DisplayName("codes are six digits, deterministic per delivery, and differ per delivery")
    void codeShape() {
        String first = proofs.handoverCode(D1);
        assertTrue(first.matches("\\d{6}"), "a code must be exactly six digits, was " + first);
        assertEquals(first, proofs.handoverCode(D1), "the same delivery always yields the same code");
        assertNotEquals(first, proofs.handoverCode(D2), "codes must be bound to their delivery");
    }

    @Test
    @DisplayName("verification accepts the code and rejects everything else")
    void verification() {
        String code = proofs.handoverCode(D1);
        assertTrue(proofs.verifyHandoverCode(D1, code));
        assertTrue(proofs.verifyHandoverCode(D1, " " + code + " "),
                "surrounding whitespace is trimmed, couriers type fast");

        assertFalse(proofs.verifyHandoverCode(D1, "000000"));
        assertFalse(proofs.verifyHandoverCode(D2, code), "a code is not transferable to another delivery");
        assertFalse(proofs.verifyHandoverCode(D1, null));
        assertFalse(proofs.verifyHandoverCode(D1, ""));
        assertFalse(proofs.verifyHandoverCode(D1, "12345"));
        assertFalse(proofs.verifyHandoverCode(D1, "1234567"));
        assertFalse(proofs.verifyHandoverCode(D1, "12a456"));
    }

    @Test
    @DisplayName("the RFC 4226 truncation matches an independent recomputation")
    void knownAnswer() throws Exception {
        // Re-derived here step by step rather than copied: any change to the
        // offset, the masking, the byte order or the modulus fails this.
        byte[] proofKey = hmac(ROOT.getBytes(StandardCharsets.UTF_8),
                "swiftdeliver/delivery-proof/v1");
        byte[] handoverKey = hmac(proofKey, "swiftdeliver/handover-code/v1");
        byte[] mac = hmac(handoverKey, "swiftdeliver/handover/v1|" + D1);

        int offset = mac[mac.length - 1] & 0x0F;
        int code = ((mac[offset] & 0x7F) << 24)
                | ((mac[offset + 1] & 0xFF) << 16)
                | ((mac[offset + 2] & 0xFF) << 8)
                | (mac[offset + 3] & 0xFF);
        assertEquals(String.format("%06d", code % 1_000_000), proofs.handoverCode(D1));
    }

    private static byte[] hmac(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
}
