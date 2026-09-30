package com.delivery.backend.modules.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stocke et relit la trace de scores d'une trajectoire.
 *
 * <p>Isolé de {@link TrajectoryFraudService} volontairement : celui-ci est une
 * classe pure, sans Redis, donc testable sans Spring ni conteneur. Cette classe
 * possède seule le format de clé {@code fraude:score:{deliveryId}}, ce qui évite
 * d'avoir l'écriture dans un service et la lecture dans un autre.
 *
 * <p>Chaque fix est conservé, pas seulement les verdicts « fraude ». Un tableau
 * de bord qui n'afficherait que les alertes donnerait l'impression que le modèle
 * hurle en permanence ; la courbe complète montre au contraire qu'il se tait sur
 * une livraison honnête et se déclenche sur une autre.
 *
 * <p>Les clés expirent : une trace qui vit éternellement garderait en mémoire le
 * score de tournées terminées il y a des jours, et une trace dupliquée d'il y a
 * six heures ne voudrait plus rien dire.
 */
@Component
public class FraudTrailStore {

    private static final Logger log = LoggerFactory.getLogger(FraudTrailStore.class);

    static final String KEY_PREFIX = "fraude:score:";

    /**
     * Assez long pour couvrir une tournée entière, assez court pour que le
     * tableau de bord ne montre pas l'historique d'un colis livré la veille.
     */
    static final Duration TTL = Duration.ofHours(6);

    /**
     * Nombre de points conservés et renvoyés.
     *
     * <p>Borné parce qu'un relevé de 400 fixes à 10 s d'intervalle ferait 1 h 10
     * de points à afficher dans une modale. Tronquer du plus ancien garde la
     * forme de la courbe, qui est ce qu'un opérateur veut lire.
     */
    static final int MAX_POINTS = 120;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * {@code StringRedisTemplate}, pas {@code RedisTemplate<String, String>}.
     *
     * <p>Le projet définit son propre bean {@code redisTemplate} et Spring
     * autoconfigure en plus {@code stringRedisTemplate}. Injecter le type générique
     * laisse deux candidats : Spring retombe alors sur le nom du paramètre pour
     * trancher, ce qui marche tant que le paramètre s'appelle exactement comme le
     * bean. C'est un piège muet — il a cassé cette classe dès son premier build
     * parce que le paramètre s'appelait {@code redis}. Le type concret ne laisse
     * qu'un candidat, et les sérialiseurs sont de toute façon identiques.
     */
    public FraudTrailStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Enregistre le verdict d'un fix.
     *
     * <p>Ne lève jamais : perdre une coordonnée de la trajectoire parce que Redis
     * est saturé serait pire que perdre la trace.
     */
    public void record(UUID deliveryId, TrajectoryFraudService.Verdict v) {
        try {
            String json = mapper.writeValueAsString(new Point(
                    System.currentTimeMillis(),
                    round(v.score(), 4),
                    v.fraud(),
                    round(v.speedMps(), 2),
                    round(v.stallSeconds(), 0),
                    round(v.distanceToTargetM(), 0),
                    round(v.threshold(), 4)));
            String key = KEY_PREFIX + deliveryId;
            // LPUSH + LTRIM : la liste est bornée à l'écriture comme à la lecture,
            // pour qu'un trajet de plusieurs heures ne grossisse pas sans fin.
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, MAX_POINTS - 1L);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("could not record fraud score for delivery {}: {}", deliveryId, e.toString());
        }
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    /**
     * Un point de la trajectoire.
     *
     * <p>{@code threshold} voyage avec le point plutôt que d'être lu une fois
     * depuis le modèle : le seuil pourrait changer au redémarrage du service, et
     * une courbe tracée avec un seuil différent de celui qui a servi à décider
     * serait un graphique qui ment.
     *
     * @param score  probabilité de fraude renvoyée par le modèle
     * @param fraud  décision prise avec le seuil de ce point
     * @param stall  secondes écoulées sans progrès vers la cible
     * @param dist   distance à la cible, en mètres
     */
    public record Point(long at, double score, boolean fraud, double speedMps,
                        double stallSeconds, double distanceToTargetM, double threshold) {
    }

    /**
     * Résumé d'une trace, pour le badge du tableau de bord.
     *
     * @param flaggedFixes nombre de fixes au-dessus du seuil
     * @param totalFixes   nombre de fixes observés
     * @param peakScore    score le plus élevé atteint
     */
    public record Trail(UUID deliveryId, int flaggedFixes, int totalFixes,
                        double peakScore, double lastScore, long lastFixAt,
                        double threshold, List<Point> points) {

        /** Au moins un fix au-dessus du seuil : c'est ce qui déclenche le badge. */
        public boolean flagged() {
            return flaggedFixes > 0;
        }

        /**
         * Fraction de fixes signalés, arrondie au pourcentage entier.
         *
         * <p>Distingué du compteur parce que 1 fix sur 3 et 40 sur 120 ne se
         * lisent pas de la même façon : le premier est une pointe isolée, le
         * second est un comportement soutenu.
         */
        public int flaggedPercent() {
            return totalFixes == 0 ? 0 : (int) Math.round(100.0 * flaggedFixes / totalFixes);
        }

        /**
         * Version allégée pour le badge, sans la courbe.
         *
         * <p>Le tableau de bord rafraîchit toutes les 10 s et peut afficher une
         * trentaine de tournées ; renvoyer 120 points pour chacune à chaque
         * rafraîchissement ferait transferring l'essentiel de la charge réseau
         * pour des données que la liste n'affiche pas. La courbe n'est demandée
         * qu'à l'ouverture du détail.
         */
        public Summary summary() {
            return new Summary(deliveryId, flagged(), flaggedFixes, points.size(),
                    flaggedPercent(), peakScore, lastScore, threshold, lastFixAt);
        }
    }

    /** Le badge : assez pour décider quoi afficher, pas assez pour tracer une courbe. */
    public record Summary(UUID deliveryId, boolean flagged, int flaggedFixes, int totalFixes,
                          int flaggedPercent, double peakScore, double lastScore,
                          double threshold, long lastFixAt) {
    }

    /**
     * Toutes les traces encore en mémoire.
     *
     * <p>Utilise KEYS, qui est O(N) sur le jeu de données. Acceptable ici : le
     * préfixe ne contient que des tournées récentes, l'appel est réservé à
     * l'administrateur, et le nombre de clés est borné par le TTL de six heures
     * plutôt que par l'historique complet. Un déploiement à grande échelle
     * demanderait un index par date ; le dire vaut mieux que de le coder en
     * douce et de le laisser exploser.
     *
     * <p>Ne rattrape pas l'erreur Redis, contrairement à {@link #record}. Les
     * deux chemins n'ont pas le même contrat : écrire une trace se fait pendant
     * le broadcast d'un livreur, où perdre la trace est moins grave que perdre
     * la position, donc on ne lève rien. Lire la liste se fait pour un tableau de
     * bord, et renvoyer une liste vide quand Redis est injoignable afficherait
     * « aucune anomalie » au superviseur alors que personne n'a rien demandé. Un
     * écran qui ne sait pas qu'il est aveugle est pire qu'un écran rouge.
     *
     * @throws org.springframework.data.redis.RedisConnectionFailureException
     *         si Redis est injoignable, pour que l'appelant montre l'erreur
     */
    public List<Trail> allTrails() {
        List<Trail> out = new ArrayList<>();
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return out;
        }
        for (String key : keys) {
            try {
                Trail t = trail(UUID.fromString(key.substring(KEY_PREFIX.length())));
                if (t != null) {
                    out.add(t);
                }
            } catch (IllegalArgumentException ignored) {
                // Clé qui ne suit pas le format : ce n'est pas notre trace.
            }
        }
        return out;
    }

    /** La trace d'une livraison, ou null si elle n'a jamais été scorée. */
    public Trail trail(UUID deliveryId) {
        List<String> raw;
        try {
            raw = redis.opsForList().range(KEY_PREFIX + deliveryId, 0, MAX_POINTS - 1L);
        } catch (Exception e) {
            log.warn("could not read fraud trail for delivery {}: {}", deliveryId, e.toString());
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        List<Point> points = parse(raw);
        if (points.isEmpty()) {
            return null;
        }

        // LPUSH inverse l'ordre : le plus récent est en tête. On rend la liste du
        // plus ancien au plus récent, parce que c'est dans cet ordre qu'une
        // courbe se lit.
        Collections.reverse(points);

        int flagged = 0;
        double peak = 0;
        for (Point p : points) {
            if (p.fraud()) {
                flagged++;
            }
            peak = Math.max(peak, p.score());
        }
        Point newest = points.get(points.size() - 1);

        return new Trail(deliveryId, flagged, points.size(), round(peak, 4),
                newest.score(), newest.at(), newest.threshold(), points);
    }

    private List<Point> parse(List<String> raw) {
        List<Point> points = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                JsonNode n = mapper.readTree(s);
                points.add(new Point(
                        n.path("at").asLong(),
                        n.path("score").asDouble(),
                        n.path("fraud").asBoolean(),
                        n.path("speedMps").asDouble(),
                        n.path("stallSeconds").asDouble(),
                        n.path("dist").asDouble(),
                        n.path("threshold").asDouble()));
            } catch (Exception ignored) {
                // Un point illisible ne doit pas faire tomber toute la courbe.
            }
        }
        return points;
    }
}