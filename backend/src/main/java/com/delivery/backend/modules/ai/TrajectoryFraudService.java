package com.delivery.backend.modules.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scoring en direct des mouvements livreur.
 *
 * <p>Chaque broadcast de position est transformé en features de mouvement puis
 * évalué par le modèle ({@link TrajectoryFraudModel}) entraîné hors-ligne. Ce
 * service détient l'état nécessaire aux features temporelles : la feature
 * « temps sans progrès » n'existe pas dans une position isolée, elle se construit
 * d'un fix à l'autre.
 *
 * <p>Une alerte n'est pas levée à chaque fix suspect : une trajectoire frauduleuse
 * en produit des dizaines d'affilée, et une console qui hurle à chaque broadcast
 * est une console qu'on finit par ignorer. On notifie une fois par
 * {@link #ALERT_COOLDOWN} tant que le score reste au-dessus du seuil.
 *
 * <p>Classe pure côté modèle : aucun accès base, aucun appel réseau. Les tests
 * peuvent donc injecter n'importe quelle feature et lire la décision.
 */
@Service
public class TrajectoryFraudService {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryFraudService.class);

    /** Distance en mètres en dessous de laquelle un fix ne compte pas comme un progrès. */
    public static final double PROGRESS_EPS_M = 25.0;

    /** Au-delà de ce délai sans progrès, le livreur est considéré à l'arrêt. */
    public static final double STALL_SUSPECT_S = 90.0;

    /**
     * Fenêtre pendant laquelle on ne renvoie pas une seconde alerte pour le même
     * livreur et la même livraison. Assez long pour couvrir une rafale de fixes
     * frauduleux, assez court pour qu'un livreur honnête arrêté à un feu rouge
     * ne déclenche qu'une fois.
     */
    public static final Duration ALERT_COOLDOWN = Duration.ofMinutes(10);

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /**
     * Cadence nominale des broadcasts, en secondes. Sert uniquement pour le tout
     * premier fix d'une tournée, où aucun intervalle réel n'existe encore. Après
     * ça, l'intervalle se mesure : c'est le script d'entraînement qui utilise
     * cette valeur, donc les deux côtés concordent.
     */
    public static final double NOMINAL_INTERVAL_S = 10.0;

    private final TrajectoryFraudModel model;

    /** État par livraison : le modèle a besoin d'historique, pas d'un point isolé. */
    private final Map<UUID, RunState> states = new ConcurrentHashMap<>();

    public TrajectoryFraudService(TrajectoryFraudModel model) {
        this.model = model;
    }

    private static final class RunState {
        double lastLat;
        double lastLng;
        double lastSpeed;
        boolean lastBearingKnown;
        double lastBearing;
        double bestDistance = Double.MAX_VALUE;
        double stallSeconds;
        double lastDistance;
        double distanceAtStart;
        boolean started;
        Instant lastAlert;
        Instant lastFixAt;
    }

    /**
     * Évalue un broadcast de position.
     *
     * @param deliveryId   livraison suivie, sert de clé d'état
     * @param lat, lng     position du livreur
     * @param targetLat    latitude de la destination de CETTE livraison
     * @param targetLng    longitude de la destination
     * @return la décision et les features qui l'ont produite
     */
    public Verdict evaluate(UUID deliveryId, double lat, double lng,
                            double targetLat, double targetLng) {
        return evaluateAt(deliveryId, lat, lng, targetLat, targetLng, Instant.now());
    }

    /**
     * Évaluation avec un horodatage de fix fourni.
     *
     * <p>La feature « temps sans progrès » se mesure en secondes écoulées, donc
     * des tests qui exécutent 40 fixes en cinq millisecondes mesureraient cinq
     * millisecondes d'arrêt et ne prouveraient rien. Passer l'instant rend la
     * simulation déterministe : le temps advances virtuellement au lieu
     * d'attendre. {@link #evaluate} délègue ici avec l'heure réelle, donc le
     * chemin testé est le chemin livré.
     */
    public Verdict evaluateAt(UUID deliveryId, double lat, double lng,
                              double targetLat, double targetLng, Instant now) {

        RunState st = states.computeIfAbsent(deliveryId, k -> new RunState());

        double distanceToTarget = haversineM(lat, lng, targetLat, targetLng);
        double dt;
        double speed;
        double accel;
        double heading;
        double headingChange;
        double bearingErr;

        if (!st.started) {
            // Premier fix : la distance de départ sert de référence au ratio de
            // progression, et il n'y a pas encore de vitesse à calculer.
            st.started = true;
            st.distanceAtStart = Math.max(distanceToTarget, 1.0);
            dt = 0;
            speed = 0;
            accel = 0;
            heading = bearingDeg(lat, lng, targetLat, targetLng);
            headingChange = 0;
            bearingErr = 0;
        } else {
            // Intervalle réel entre deux fixes. Un téléphone qui perd le signal
            // laisse un intervalle plus long, et diviser par la cadence nominale
            // gonflerait la vitesse : le modèle apprendrait que du bruit est normal.
            dt = st.lastFixAt == null ? NOMINAL_INTERVAL_S
                    : Math.max(Duration.between(st.lastFixAt, now).toMillis() / 1000.0, 1.0);

            double step = haversineM(st.lastLat, st.lastLng, lat, lng);
            speed = step / dt;
            accel = (speed - st.lastSpeed) / dt;
            heading = bearingDeg(st.lastLat, st.lastLng, lat, lng);
            headingChange = st.lastBearingKnown ? angleDiffDeg(heading, st.lastBearing) : 0;
            bearingErr = Math.min(angleDiffDeg(heading, bearingDeg(lat, lng, targetLat, targetLng)), 180.0);
        }

        // Temps sans progrès réel. C'est la feature qui distingue un livreur garé
        // à deux kilomètres de la destination d'un livreur à un feu rouge : dans
        // les deux cas vitesse, accélération et cap ne bougent pas, mais l'un
        // approaches et l'autre non.
        if (distanceToTarget < st.bestDistance - PROGRESS_EPS_M) {
            st.bestDistance = distanceToTarget;
            st.stallSeconds = 0;
        } else {
            st.stallSeconds += dt;
        }

        double progressRatio = 1.0 - Math.min(distanceToTarget / st.distanceAtStart, 1.0);
        double progressRate = st.started && st.lastFixAt != null
                ? (st.lastDistance - distanceToTarget) / dt : 0.0;

        double[] features = {
                speed, accel, distanceToTarget, progressRatio,
                headingChange, bearingErr, st.stallSeconds, progressRate
        };

        double score = model.score(features);
        boolean fraud = score >= model.threshold();

        // Mémoriser l'état pour le prochain fix.
        st.lastLat = lat;
        st.lastLng = lng;
        st.lastSpeed = speed;
        st.lastBearing = heading;
        st.lastBearingKnown = true;
        st.lastDistance = distanceToTarget;
        st.lastFixAt = now;

        boolean shouldNotify = false;
        if (fraud) {
            if (st.lastAlert == null
                    || Duration.between(st.lastAlert, now).compareTo(ALERT_COOLDOWN) > 0) {
                st.lastAlert = now;
                shouldNotify = true;
            }
        } else {
            // Reprise normale : le cooldown repart de zéro, sinon un livreur
            // honnête resterait muet pendant dix minutes après un faux signal.
            st.lastAlert = null;
        }

        return new Verdict(score, model.threshold(), fraud, shouldNotify, features,
                speed, st.stallSeconds, distanceToTarget);
    }

    public TrajectoryFraudModel model() {
        return model;
    }

    /**
     * Oublie l'historique d'une livraison. Appelé à la fin d'une tournée : sans ça
     * l'état reste en mémoire pour toujours et la feature de temps sans progrès
     * repart d'un reliquat de la livraison précédente.
     */
    public void forget(UUID deliveryId) {
        states.remove(deliveryId);
    }

    public static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static double bearingDeg(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLam = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLam) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLam);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    public static double angleDiffDeg(double a, double b) {
        return Math.abs((a - b + 180.0) % 360.0 - 180.0);
    }

    /**
     * Résultat d'un broadcast.
     *
     * @param fraud        le score dépasse le seuil
     * @param shouldNotify une alerte doit partir : première fois seulement,
     *                     les suivantes tombent dans le cooldown
     */
    public record Verdict(double score, double threshold, boolean fraud, boolean shouldNotify,
                          double[] features, double speedMps, double stallSeconds,
                          double distanceToTargetM) {
    }
}