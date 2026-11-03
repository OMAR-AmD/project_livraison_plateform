package com.delivery.backend.modules.field;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Réglages de la collecte terrain (#5a).
 *
 * <p>Désactivée par défaut : un déploiement de démonstration ne doit pas
 * commencer à écrire des traces parce qu'il a redémarré. Le runbook
 * {@code FIELD_COLLECTION.md} explique comment l'activer pour une campagne.
 */
@Component
@ConfigurationProperties(prefix = "application.field-collection")
public class FieldCollectionProperties {

    /** Interrupteur global. */
    private boolean enabled = false;

    /** Exiger le consentement explicite du coursier. */
    private boolean requireConsent = true;

    /** Durée de conservation des traces, en jours. */
    private int retentionDays = 90;

    /** Objectif de runs terrain pour le Sprint 2. */
    private int targetRuns = 150;

    /** Taille de la file d'écriture asynchrone. */
    private int queueCapacity = 1000;

    /** Version du formulaire de consentement stockée avec chaque run. */
    private String consentVersion = "field-v1";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isRequireConsent() { return requireConsent; }
    public void setRequireConsent(boolean requireConsent) { this.requireConsent = requireConsent; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public int getTargetRuns() { return targetRuns; }
    public void setTargetRuns(int targetRuns) { this.targetRuns = targetRuns; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public String getConsentVersion() { return consentVersion; }
    public void setConsentVersion(String consentVersion) { this.consentVersion = consentVersion; }
}
