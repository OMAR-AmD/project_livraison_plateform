package com.delivery.backend.modules.delivery;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Sprint 1 — Proof of Delivery cryptographique (anti-répudiation).
 *
 * <p>Problème métier : le client affirme ne rien avoir reçu, le livreur affirme
 * avoir livré. Sans preuve scellée, c'est parole contre parole.
 *
 * <p>Solution : à l'instant du passage à DELIVERED, le backend scelle la preuve
 * dans un hash SHA-256 couvrant l'identifiant de la commande, les deux parties,
 * la position GPS réelle du livreur et l'horodatage. Toute falsification
 * ultérieure d'un seul champ invalide le hash, vérifiable via {@link #verify}.
 *
 * <p>Avant de sceller, la position du livreur est comparée à la destination
 * (formule de Haversine). Au-delà du seuil, la livraison est refusée : on ne
 * peut pas "livrer" depuis l'autre bout de la ville.
 *
 * <p>Classe pure (aucune dépendance Spring) pour être testée unitairement.
 */
@Service
public class DeliveryProofService {

    /** Rayon terrestre en mètres (WGS-84). */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /**
     * Distance maximale tolérée entre le livreur et la destination au moment
     * de la livraison. 500 m couvrent l'imprécision GPS urbaine (immeubles,
     * ruelles) tout en bloquant une fausse livraison à distance.
     */
    public static final double MAX_DELIVERY_DISTANCE_M = 500.0;

    /** Distance Haversine en mètres entre deux points WGS-84. */
    public double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    /** Charge utile scellée : tout champ modifié invalide le hash. */
    public String buildPayload(UUID deliveryId, UUID courierId, UUID clientId,
                               double lat, double lng, LocalDateTime timestamp) {
        return String.join("|",
                deliveryId.toString(),
                courierId.toString(),
                clientId.toString(),
                Double.toString(lat),
                Double.toString(lng),
                timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    /** SHA-256 hexadécimal du payload. */
    public String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /**
     * Recalcule le hash depuis les champs stockés et le compare à celui scellé.
     * Retourne false si un seul champ a été modifié après la livraison.
     */
    public boolean verify(UUID deliveryId, UUID courierId, UUID clientId,
                          double lat, double lng, LocalDateTime timestamp, String expectedHash) {
        if (expectedHash == null) {
            return false;
        }
        String recomputed = sha256Hex(buildPayload(deliveryId, courierId, clientId, lat, lng, timestamp));
        return MessageDigest.isEqual(
                recomputed.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
