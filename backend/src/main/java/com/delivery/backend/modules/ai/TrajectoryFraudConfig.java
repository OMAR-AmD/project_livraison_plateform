package com.delivery.backend.modules.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chargement du modèle de fraude au démarrage.
 *
 * <p>Le modèle est lu une fois et mis en cache comme singleton : le charger à la
 * première requête ferait payer 300 KB de JSON et des milliers de nœuds à chaque
 * broadcast de position, ce qui est le chemin le plus chaud du service.
 *
 * <p>Si le fichier est absent, le service ne démarre pas. C'est délibéré et c'est
 * le contraire d'un `if (model != null)` silencieux : une plateforme de livraison
 * qui évaluerait des trajectoires sans modèle laisserait croire que la
 * détection fonctionne. Un démarrage bruyant vaut mieux qu'une protection
 * imaginaire. Pour rejouer la démo après avoir modifié le simulateur :
 * {@code python ml/train_fraud_model.py}.
 */
@Configuration
public class TrajectoryFraudConfig {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryFraudConfig.class);

    @Bean
    public TrajectoryFraudModel trajectoryFraudModel() {
        TrajectoryFraudModel model = TrajectoryFraudModel.load();
        log.info("trajectory fraud detector ready: {} trees, threshold {}",
                model.treeCount(), model.threshold());
        if (model.trainedOnSyntheticData()) {
            log.warn("trajectory fraud model was trained on SYNTHETIC city traffic. "
                    + "No real courier history exists yet, so scores are not field "
                    + "accuracy. Re-run ml/train_fraud_model.py once real data is available.");
        }
        return model;
    }
}