package com.delivery.backend.modules.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proof-of-delivery seal.
 *
 * <p>These tests exist because the previous version sealed with plain SHA-256,
 * which looked convincing and proved nothing: every field in the payload is known
 * to both parties, so either of them can recompute the hash, and the party
 * disputing the delivery is exactly the party who would. The tests that matter
 * most here are therefore the ones about what a party <i>without the key</i>
 * cannot do.
 */
class DeliveryProofServiceTest {

    private static final String ROOT_SECRET =
            "test-root-secret-0123456789abcdef0123456789abcdef";

    private static final UUID DELIVERY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COURIER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CLIENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final double LAT = 48.8566;
    private static final double LNG = 2.3522;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 678_901_000);

    /**
     * Computed independently of this codebase, from the written payload spec, with
     * Python's hmac module. Pinning the literal here is the point: if someone
     * reorders the payload fields or drops the version tag, every seal in the
     * database silently stops verifying, and this is the only test that would
     * notice before a courier's delivery is reported as tampered with.
     */
    private static final String EXPECTED_PAYLOAD =
            "v1|11111111-1111-1111-1111-111111111111|22222222-2222-2222-2222-222222222222"
                    + "|33333333-3333-3333-3333-333333333333|48.8566|2.3522"
                    + "|2026-01-02T03:04:05.678901";

    private static final String EXPECTED_SEAL =
            "f543ff85cd2888d759ac9ab624141b3eb5954dfb8e9216c3569172f2738bb378";

    private DeliveryProofService service() {
        return new DeliveryProofService(ROOT_SECRET);
    }

    // ---------------------------------------------------------------- the seal

    @Test
    @DisplayName("payload and seal match an independent implementation")
    void knownAnswer() {
        DeliveryProofService svc = service();

        assertAll(
                () -> assertEquals(EXPECTED_PAYLOAD,
                        svc.buildPayload(DELIVERY, COURIER, CLIENT, LAT, LNG, AT)),
                () -> assertEquals(EXPECTED_SEAL,
                        svc.seal(DELIVERY, COURIER, CLIENT, LAT, LNG, AT).hash()),
                () -> assertEquals(64, svc.seal(DELIVERY, COURIER, CLIENT, LAT, LNG, AT)
                        .hash().length(), "HMAC-SHA256 is 32 bytes, i.e. 64 hex characters"));
    }

    @Test
    @DisplayName("the seal is not the root secret used directly as the MAC key")
    void keySeparation() throws Exception {
        String seal = service().seal(DELIVERY, COURIER, CLIENT, LAT, LNG, AT).hash();

        // What the seal would be with no derivation step. If someone "simplifies"
        // DeliveryProofService by dropping deriveKey, this is what catches it --
        // and the consequence would be that the JWT signing key and the proof key
        // are the same bytes, so a JWT key leak also forges delivery proofs.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ROOT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String underivedSeal = HexFormat.of().formatHex(
                mac.doFinal(EXPECTED_PAYLOAD.getBytes(StandardCharsets.UTF_8)));

        assertNotEquals(underivedSeal, seal,
                "the seal must be keyed by a purpose-derived key, not the root secret");
    }

    // ------------------------------------------------------------ verification

    @Test
    @DisplayName("a freshly sealed delivery verifies")
    void roundTrip() {
        DeliveryProofService svc = service();
        DeliveryProofService.Seal seal = svc.seal(DELIVERY, COURIER, CLIENT, LAT, LNG, AT);

        assertTrue(svc.verify(DELIVERY, COURIER, CLIENT, LAT, LNG, seal.sealedAt(), seal.hash()));
    }

    @Test
    @DisplayName("altering any single sealed field fails verification")
    void tamperingIsDetected() {
        DeliveryProofService svc = service();
        DeliveryProofService.Seal seal = svc.seal(DELIVERY, COURIER, CLIENT, LAT, LNG, AT);
        String hash = seal.hash();
        LocalDateTime at = seal.sealedAt();

        assertAll(
                () -> assertFalse(svc.verify(UUID.randomUUID(), COURIER, CLIENT, LAT, LNG, at, hash),
                        "moving the proof to another delivery"),
                () -> assertFalse(svc.verify(DELIVERY, UUID.randomUUID(), CLIENT, LAT, LNG, at, hash),
                        "reattributing the proof to another courier"),
                () -> assertFalse(svc.verify(DELIVERY, COURIER, UUID.randomUUID(), LAT, LNG, at, hash),
                        "reattributing the proof to another client"),
                () -> assertFalse(svc.verify(DELIVERY, COURIER, CLIENT, LAT + 0.0001, LNG, at, hash),
                        "editing where the courier was"),
                () -> assertFalse(svc.verify(DELIVERY, COURIER, CLIENT, LAT, LNG + 0.0001, at, hash),
                        "editing the longitude"),
                () -> assertFalse(svc.verify(DELIVERY, COURIER, CLIENT, LAT, LNG,
                        at.plusSeconds(1), hash), "editing when it happened"));
    }

    @Test
    @DisplayName("a seal from one key does not verify under another")
    void keyChangeInvalidatesSeals() {
        DeliveryProofService original = new DeliveryProofService(ROOT_SECRET);
        DeliveryProofService rotated = new DeliveryProofService(ROOT_SECRET + "-rotated");

        DeliveryProofService.Seal seal = original.seal(DELIVERY, COURIER, CLIENT, LAT, LNG, AT);

        assertFalse(rotated.verify(DELIVERY, COURIER, CLIENT, LAT, LNG, seal.sealedAt(), seal.hash()),
                "rotating the root secret must not leave old seals importable");
    }

    @Test
    @DisplayName("a null seal never verifies")
    void nullSealIsRejected() {
        assertFalse(service().verify(DELIVERY, COURIER, CLIENT, LAT, LNG, AT, null),
                "a delivery saved before the proof feature must not read as verified");
    }

    // -------------------------------------------------------------- precision

    @Test
    @DisplayName("the sealed timestamp is truncated to what the database can store")
    void timestampIsTruncated() {
        DeliveryProofService svc = service();

        // What actually happens at runtime: the clock hands over nanosecond
        // precision, the column keeps microseconds.
        LocalDateTime withNanos = AT.plusNanos(512);
        DeliveryProofService.Seal seal = svc.seal(DELIVERY, COURIER, CLIENT, LAT, LNG, withNanos);

        assertAll(
                () -> assertEquals(0, seal.sealedAt().getNano() % 1_000,
                        "sealed timestamp must survive a round trip through the column"),
                // Verifying with the value that was returned must succeed, which is
                // the whole point: the caller persists seal.sealedAt(), and later
                // re-derives from it.
                () -> assertTrue(svc.verify(DELIVERY, COURIER, CLIENT, LAT, LNG,
                        seal.sealedAt(), seal.hash())),
                // And the sub-microsecond remainder must not have been sealed, or
                // the stored value could never reproduce the hash.
                () -> assertEquals(
                        svc.seal(DELIVERY, COURIER, CLIENT, LAT, LNG, AT).hash(),
                        seal.hash(),
                        "nanoseconds beyond microsecond precision must not enter the payload"));
    }

    // ------------------------------------------------------------------ setup

    @Test
    @DisplayName("a missing or blank key refuses to be constructed")
    void keyMustBeConfigured() {
        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> new DeliveryProofService(null)),
                () -> assertThrows(IllegalStateException.class, () -> new DeliveryProofService("")),
                () -> assertThrows(IllegalStateException.class, () -> new DeliveryProofService("   ")));
    }

    // --------------------------------------------------------------- geometry

    @Test
    @DisplayName("haversine distance is right at the scale that matters")
    void haversine() {
        DeliveryProofService svc = service();

        // 0.009 degrees of latitude is very close to 1 km at the radius this uses
        // (pi/180 * 6_371_000 = 111_194.9 m per degree). Asserting a range and not
        // a literal keeps the test meaningful without pinning a rounding.
        double oneKm = svc.haversineMeters(48.8566, 2.3522, 48.8656, 2.3522);

        assertAll(
                () -> assertEquals(0.0, svc.haversineMeters(LAT, LNG, LAT, LNG), 0.001),
                () -> assertTrue(oneKm > 990 && oneKm < 1010,
                        "expected about 1001 m, measured " + Math.round(oneKm)),
                // Symmetry: the distance is the same in both directions.
                () -> assertEquals(oneKm, svc.haversineMeters(48.8656, 2.3522, 48.8566, 2.3522),
                        0.001));
    }
}
