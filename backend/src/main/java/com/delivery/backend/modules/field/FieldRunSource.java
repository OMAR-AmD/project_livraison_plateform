package com.delivery.backend.modules.field;

/**
 * Origine d'une trace collectée.
 *
 * <p>Seules les traces {@link #FIELD} comptent vers l'objectif de 150 runs
 * réels. Les runs {@link #SIMULATED} et {@link #TEST} restent stockés pour le
 * débogage et les démonstrations, mais ne doivent jamais être comptés comme
 * des données terrain.
 */
public enum FieldRunSource {
    FIELD,
    SIMULATED,
    TEST
}
