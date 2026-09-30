package com.delivery.backend.modules.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Évaluation en ligne du modèle de fraude entraîné hors-ligne (Random Forest).
 *
 * <p><b>Ce que c'est.</b> Le modèle est entraîné dans {@code ml/train_fraud_model.py}
 * et exporté en JSON. Cette classe le charge au démarrage et l'évalue en mémoire,
 * à chaque broadcast de position. L'entraînement est hors-ligne, l'inférence est
 * dans le produit : c'est le seul découpage qui tient dans un seul JAR, donc
 * démontrable sans service Python à lancer à côté.
 *
 * <p><b>Pourquoi pas un Isolation Forest.</b> Il a été construit et mesuré d'abord.
 * Il ne battait pas un seuil {@code speed > 25 m/s} : même rappel sur la fraude
 * qu'une règle voit déjà, et il ne voyait jamais le stationnement qui caractérise
 * {@code drift_then_vanish}. Comparaison reproductible avec
 * {@code python ml/supervised_vs_unsupervised.py}. Une règle qui égale le modèle
 * n'est pas une innovation, alors le modèle non supervisé a été écarté sur des
 * mesures et non conservé parce qu'il sonnait mieux.
 *
 * <p><b>Pourquoi un Random Forest gagne.</b> « Non supervisé » veut dire « sans
 * labels », et sans labels la seule chose d'anormale chez un livreur garé est que
 * rien ne bouge. Vitesse à zéro, accélération à zéro, cap inchangé : un livreur à
 * un feu rouge est identique. Les distinguer demande l'interaction (« toujours
 * loin de la destination ET rien ne change »), que les arbres supervisés
 * apprennent et qu'une isolation unidimensionnelle n'apprend pas.
 *
 * <p><b>Honnêteté sur les données.</b> L'entraînement est synthétique : personne
 * n'a conduit cette plateforme assez longtemps pour produire un historique réel.
 * Les chiffres du rapport viennent de {@code ml/}, reproductibles en relançant
 * le script, et décrivent une chaîne de traitement qui fonctionne — pas une
 * performance mesurée en conditions réelles.
 *
 * <p>Classe pure (aucune dépendance Spring) pour être testée unitairement contre
 * les scores scikit-learn de {@code ml/trajectory_fraud_rf_parity.json}.
 */
public final class TrajectoryFraudModel {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryFraudModel.class);

    /** Ressource embarquée ; le .gz est écrit par le script d'entraînement. */
    public static final String RESOURCE = "model/trajectory_fraud_rf.json.gz";

    /** Nombre de features attendues par le modèle exporté. */
    public static final int FEATURE_COUNT = 8;

    private record Node(int left, int right, int feature, double threshold,
                        double value, boolean leaf) {
    }

    private final List<List<Node>> trees;
    private final double threshold;
    private final boolean synthetic;

    private TrajectoryFraudModel(List<List<Node>> trees, double threshold, boolean synthetic) {
        this.trees = trees;
        this.threshold = threshold;
        this.synthetic = synthetic;
    }

    /**
     * Charge le modèle depuis le classpath. Le JSON brut est aussi accepté : sur
     * une machine de développement sans l'étape de compression, le service
     * démarre quand même plutôt que de refuser de démarrer.
     */
    public static TrajectoryFraudModel load() {
        return load("/" + RESOURCE, "/" + RESOURCE.replace(".gz", ""));
    }

    static TrajectoryFraudModel load(String gzResource, String plainResource) {
        String body = readResource(gzResource);
        if (body == null) {
            body = readResource(plainResource);
        }
        if (body == null) {
            throw new IllegalStateException(
                    "trajectory fraud model not found on the classpath (" + gzResource
                            + " or " + plainResource + "). Run ml/train_fraud_model.py.");
        }
        return parse(body);
    }

    private static String readResource(String path) {
        try (InputStream in = TrajectoryFraudModel.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(
                    path.endsWith(".gz") ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder(1 << 20);
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            }
        } catch (IOException e) {
            log.warn("could not read {}: {}", path, e.toString());
            return null;
        }
    }

    static TrajectoryFraudModel parse(String json) {
        JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("trajectory fraud model is not valid JSON", e);
        }

        double threshold = root.path("threshold").asDouble(Double.NaN);
        if (Double.isNaN(threshold)) {
            throw new IllegalStateException("trajectory fraud model has no threshold");
        }

        List<List<Node>> trees = new ArrayList<>();
        for (JsonNode t : root.path("trees")) {
            List<Node> nodes = new ArrayList<>(t.size());
            for (JsonNode n : t) {
                nodes.add(new Node(
                        n.path("left").asInt(-1),
                        n.path("right").asInt(-1),
                        n.path("feature").asInt(-1),
                        n.path("threshold").asDouble(Double.NaN),
                        n.path("value").asDouble(0.0),
                        n.path("isLeaf").asBoolean(false)));
            }
            if (!nodes.isEmpty()) {
                trees.add(List.copyOf(nodes));
            }
        }
        if (trees.isEmpty()) {
            throw new IllegalStateException("trajectory fraud model contains no trees");
        }

        boolean synthetic = root.path("synthetic").asBoolean(false);
        log.info("trajectory fraud model loaded: {} trees, threshold {}, synthetic={}",
                trees.size(), threshold, synthetic);
        return new TrajectoryFraudModel(trees, threshold, synthetic);
    }

    /**
     * Probabilité que ce mouvement soit frauduleux, dans [0, 1].
     *
     * <p>Reproduit {@code predict_proba} de scikit-learn : chaque arbre descend
     * jusqu'à une feuille, rend la probabilité moyenne de classe atteinte, et la
     * forêt est la moyenne de ces valeurs. Le test de parité le vérifie sur 200
     * vecteurs fournis par Python, parce que « ça devrait être pareil » n'est pas
     * une preuve.
     */
    public double score(double[] features) {
        if (features == null || features.length != FEATURE_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + FEATURE_COUNT + " features, got "
                            + (features == null ? "null" : features.length));
        }

        double sum = 0.0;
        for (List<Node> tree : trees) {
            int i = 0;
            // Depth-bounded so a malformed model cannot spin here.
            int guard = tree.size() + 1;
            while (guard-- > 0) {
                Node n = tree.get(i);
                if (n.leaf()) {
                    sum += n.value();
                    break;
                }
                int feature = n.feature();
                double v = (feature < 0 || feature >= features.length)
                        ? Double.NaN : features[feature];
                // sklearn routes NaN left, so an unmeasured feature never
                // silently reads as "normal".
                boolean goLeft = Double.isNaN(v) || v <= n.threshold();
                int next = goLeft ? n.left() : n.right();
                if (next < 0 || next >= tree.size()) {
                    sum += n.value();
                    break;
                }
                i = next;
            }
        }
        return sum / trees.size();
    }

    /** Le mouvement dépasse-t-il le seuil appris sur les données honnêtes ? */
    public boolean isFraud(double[] features) {
        return score(features) >= threshold;
    }

    public double threshold() {
        return threshold;
    }

    public int treeCount() {
        return trees.size();
    }

    /**
     * Les données d'entraînement sont-elles synthétiques ? Affiché dans l'UI pour
     * que personne ne puisse lire un score comme une mesure de performance en
     * conditions réelles.
     */
    public boolean trainedOnSyntheticData() {
        return synthetic;
    }
}