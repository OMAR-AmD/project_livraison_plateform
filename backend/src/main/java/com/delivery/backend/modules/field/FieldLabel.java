package com.delivery.backend.modules.field;

/**
 * Labels acceptés pour un run terrain.
 *
 * <p>{@link #EXCLUDE} sert aux runs inutilisables (GPS perdu, trajet
 * interrompu, donnée de test) : ils restent en base pour l'audit mais ne
 * doivent pas entrer dans l'entraînement.
 */
public enum FieldLabel {
    HONEST,
    TELEPORT,
    SPRINT,
    DRIFT_THEN_VANISH,
    LOOP,
    EXCLUDE
}
