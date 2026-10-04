package com.delivery.backend.modules.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Proof of delivery: an unforgeable seal over who delivered what, where, when.
 *
 * <p><b>The business problem.</b> The client says nothing arrived; the courier
 * says it did. Without a sealed record it is one person's word against another,
 * and the platform has to pick a side with no evidence.
 *
 * <p><b>Why this is an HMAC and not a hash.</b> An earlier version of this class
 * sealed with plain SHA-256 over the delivery fields. That proves nothing: the
 * fields are all known to both parties, so either of them can recompute the same
 * hash, and the party disputing the delivery is exactly the party who would. A
 * hash detects accidental corruption, never a motivated forgery. The seal is
 * therefore an HMAC-SHA256 under a key only the server holds — without that key
 * a valid seal cannot be produced for a delivery that did not happen.
 *
 * <p><b>Key separation.</b> The HMAC key is not the JWT signing key even though
 * both are derived from the same root secret. It is derived as
 * {@code HMAC(rootSecret, "swiftdeliver/delivery-proof/v1")}, so the two keys are
 * different byte strings and a weakness in one does not transfer to the other.
 * Using one secret for two purposes is a real and easily missed flaw; deriving a
 * purpose-bound key costs one HMAC and removes it.
 *
 * <p><b>What is bound into the seal.</b> The delivery, the courier and the client
 * are all in the payload, so a valid seal cannot be transplanted onto a different
 * delivery or reattributed to a different courier.
 *
 * <p><b>Clocking.</b> {@link #seal} returns the timestamp it actually hashed,
 * truncated to the precision the database can store, and callers must persist
 * that returned value. This is not tidiness: {@code LocalDateTime.now()} carries
 * nanoseconds, Postgres {@code timestamp} stores microseconds, so sealing the
 * in-memory value and later re-deriving from the stored row would recompute a
 * different payload and report an honest delivery as tampered with. Returning
 * both from one call makes the two values impossible to let drift apart.
 */
@Service
public class DeliveryProofService {

    /** Earth's mean radius in metres (WGS-84). */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /**
     * Maximum tolerated distance between the courier and the destination at the
     * moment of delivery. 500 m absorbs urban GPS error (buildings, narrow
     * streets) while refusing a delivery claimed from the other side of the city.
     */
    public static final double MAX_DELIVERY_DISTANCE_M = 500.0;

    private static final String MAC_ALGORITHM = "HmacSHA256";

    /** Context string binding the derived key to this one purpose. */
    private static final String KEY_DERIVATION_CONTEXT = "swiftdeliver/delivery-proof/v1";

    /**
     * Version tag inside the payload. If the field set ever changes, a new tag
     * keeps old seals verifiable instead of silently invalidating them.
     */
    private static final String PAYLOAD_VERSION = "v1";

    /** The database stores microseconds; hash exactly what can be stored. */
    private static final ChronoUnit STORED_PRECISION = ChronoUnit.MICROS;

    private final byte[] proofKey;

    /**
     * Separate subkey for handover codes. A code is HMAC-verified, never
     * stored, so leaking the deliveries table reveals no codes; and a code
     * cannot be mistaken for a proof seal because the derivation contexts differ.
     */
    private static final String HANDOVER_KEY_CONTEXT = "swiftdeliver/handover-code/v1";

    private final byte[] handoverKey;

    public DeliveryProofService(@Value("${application.proof.hmac-key}") String rootSecret) {
        if (rootSecret == null || rootSecret.isBlank()) {
            // Fail loud rather than defaulting. A well-known fallback key would
            // make every seal in the system forgeable by anyone who has read the
            // repository, and nothing would look wrong.
            throw new IllegalStateException(
                    "application.proof.hmac-key must be configured: without it any client "
                            + "could forge a valid proof of delivery");
        }
        this.proofKey = deriveKey(rootSecret);
        this.handoverKey = hmacRaw(proofKey, HANDOVER_KEY_CONTEXT);
    }

    /** A seal together with the exact timestamp that was sealed. */
    public record Seal(String hash, LocalDateTime sealedAt) {
    }

    /**
     * Derives the purpose-bound proof key from the root secret.
     *
     * <p>HMAC-SHA256 produces 32 bytes, i.e. a 256-bit key, which is the full
     * output size of the underlying hash and does not need stretching.
     */
    private static byte[] deriveKey(String rootSecret) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(rootSecret.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM));
            return mac.doFinal(KEY_DERIVATION_CONTEXT.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not derive the delivery-proof key", e);
        }
    }

    /** Haversine distance in metres between two WGS-84 points. */
    public double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    /** Canonical timestamp: truncated to the precision the database can hold. */
    public static LocalDateTime canonicalTimestamp(LocalDateTime timestamp) {
        return timestamp.truncatedTo(STORED_PRECISION);
    }

    /** The exact string that is sealed. Everything in it is bound by the MAC. */
    public String buildPayload(UUID deliveryId, UUID courierId, UUID clientId,
                               double lat, double lng, LocalDateTime timestamp) {
        return String.join("|",
                PAYLOAD_VERSION,
                deliveryId.toString(),
                courierId.toString(),
                clientId.toString(),
                Double.toString(lat),
                Double.toString(lng),
                timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    /**
     * Seals a delivery and returns both the MAC and the timestamp that was
     * sealed, truncated to stored precision. Callers must persist
     * {@link Seal#sealedAt()} rather than their own {@code now()}.
     */
    public Seal seal(UUID deliveryId, UUID courierId, UUID clientId,
                     double lat, double lng, LocalDateTime timestamp) {
        LocalDateTime canonical = canonicalTimestamp(timestamp);
        String payload = buildPayload(deliveryId, courierId, clientId, lat, lng, canonical);
        return new Seal(hmacHex(payload), canonical);
    }

    /** Hex HMAC-SHA256 of the payload under the derived proof key. */
    public String hmacHex(String payload) {
        return HexFormat.of().formatHex(hmacRaw(proofKey, payload));
    }

    private static byte[] hmacRaw(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable on this JVM", e);
        }
    }

    /**
     * Six-digit handover code for a delivery, shown by the client and typed or
     * scanned by the courier at the door.
     *
     * <p>RFC 4226 dynamic truncation over an HMAC of the delivery ID: 31 bits,
     * six digits, zero-padded. Deterministic per delivery and verifiable
     * without storage; single-use is enforced by the order lifecycle (a code
     * for a DELIVERED or CANCELLED order is refused, and sealing moves the
     * order to DELIVERED). A forwarded photo of the code defeats it — the code
     * proves code-presence, GPS proves place, and the proof records which
     * factors sealed it.
     */
    public String handoverCode(UUID deliveryId) {
        byte[] mac = hmacRaw(handoverKey, "swiftdeliver/handover/v1|" + deliveryId);
        int offset = mac[mac.length - 1] & 0x0F;
        int code = ((mac[offset] & 0x7F) << 24)
                | ((mac[offset + 1] & 0xFF) << 16)
                | ((mac[offset + 2] & 0xFF) << 8)
                | (mac[offset + 3] & 0xFF);
        return String.format("%06d", code % 1_000_000);
    }

    /** Constant-time comparison; anything malformed is simply not the code. */
    public boolean verifyHandoverCode(UUID deliveryId, String code) {
        if (code == null || !code.trim().matches("\\d{6}")) {
            return false;
        }
        byte[] expected = handoverCode(deliveryId).getBytes(StandardCharsets.UTF_8);
        byte[] actual = code.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Recomputes the seal from the stored fields and compares it with the one on
     * record, in constant time. Returns false if any single field was altered
     * after the delivery — including a field altered by someone who has read the
     * database but does not hold the key.
     */
    public boolean verify(UUID deliveryId, UUID courierId, UUID clientId,
                          double lat, double lng, LocalDateTime timestamp, String expectedSeal) {
        if (expectedSeal == null) {
            return false;
        }
        String recomputed = seal(deliveryId, courierId, clientId, lat, lng, timestamp).hash();
        return MessageDigest.isEqual(
                recomputed.getBytes(StandardCharsets.UTF_8),
                expectedSeal.getBytes(StandardCharsets.UTF_8));
    }
}