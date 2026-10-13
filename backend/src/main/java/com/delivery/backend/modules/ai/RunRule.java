package com.delivery.backend.modules.ai;

import java.util.List;

/**
 * La règle qui décide qu'une tournée est signalée, partagée par les deux
 * endroits qui doivent répondre la même chose.
 *
 * <p><b>Pourquoi une règle de tournée et pas un fix isolé.</b> Le seuil du modèle
 * se juge fix par fix : au taux de faux positifs visé, une tournée honnête peut
 * produire une pointe isolée, et badger dès le premier fix signalait 26 tournées
 * honnêtes sur 60 (43 %) dans la simulation bruitée. La règle retenue est celle
 * qui a été mesurée hors ligne :
 *
 * <pre>
 *   signalée  =  k=3 fixes signalés consécutifs
 *             OU un seul fix à score ≥ 0,97
 * </pre>
 *
 * <p>La clause « un seul fix » n'est pas un assouplissement, c'est une
 * récupération : certains motifs (téléportation) ne produisent qu'un unique fix
 * très chaud, et k=3 seul laissait tomber leur rappel à 28 %. Le seuil haut de la
 * clause a été réglé sur un jeu de graines puis reporté sur des graines
 * disjointes, exactement comme k.
 *
 * <p><b>Une seule implémentation.</b> L'alerte temps réel
 * ({@link TrajectoryFraudService}) et le badge du tableau de bord
 * ({@link FraudTrailStore}) doivent appliquer la même règle : deux copies
 * dériveraient au premier réglage, et un badge qui contredit l'alerte est pire
 * que pas de badge. D'où un point d'entrée « en flux » (le compteur de fixes
 * consécutifs est déjà dans l'état) et un point d'entrée « par lot » (on relit
 * une trace enregistrée), qui partagent les mêmes constantes et la même
 * décision.
 */
public final class RunRule {

    /** Nombre de fixes signalés consécutifs qui déclenchent la règle. */
    public static final int CONSECUTIVE_FLAGGED_FIXES = 3;

    /**
     * Score au-dessus duquel un fix unique suffit. T = 0,97 a été réglé sur le
     * plafond des fixes honnêtes d'un jeu de graines, puis vérifié sur des
     * graines disjointes (voir {@code ml/measure_or_clause.py}).
     */
    public static final double HIGH_CONFIDENCE_SCORE = 0.97;

    private RunRule() {
    }

    /**
     * Forme « en flux », appelée à chaque fix pendant une tournée.
     *
     * @param consecutiveFlags combien de fixes signalés d'affilée EN COMPTANT
     *                         celui-ci (0 si ce fix n'est pas signalé)
     * @param score            score de ce fix
     */
    public static boolean flagged(int consecutiveFlags, double score) {
        return consecutiveFlags >= CONSECUTIVE_FLAGGED_FIXES || score >= HIGH_CONFIDENCE_SCORE;
    }

    /**
     * Forme « par lot », sur une trace relue du plus ancien au plus récent.
     *
     * <p>Les deux listes doivent avoir la même longueur et être alignées ; elles
     * viennent de la même {@code Trail}.
     */
    public static boolean runFlagged(List<Boolean> flags, List<Double> scores) {
        int run = 0;
        for (int i = 0; i < flags.size(); i++) {
            run = Boolean.TRUE.equals(flags.get(i)) ? run + 1 : 0;
            if (flagged(run, scores.get(i))) {
                return true;
            }
        }
        return false;
    }
}
