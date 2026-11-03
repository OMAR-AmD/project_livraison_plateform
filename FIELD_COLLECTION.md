# Field collection (#5a) — runbook

> **Statut :** pipeline prêt, collecte **désactivée par défaut**.
> **Objectif Sprint 2 :** 150 runs réels labellisés, puis réentraînement v2 (#5b).
> **Limite actuelle :** 0/150. Le modèle livré reste entraîné sur des données synthétiques.

---

## 1. Ce que fait le pipeline

À chaque mise à jour GPS d'une livraison `IN_TRANSIT`, le backend :

1. calcule les 8 features de mouvement et le score du Random Forest ;
2. si la collecte est activée **et** le coursier consentant, écrit une ligne
   `field_trace_points` et met à jour `field_runs` ;
3. ne bloque jamais le broadcast GPS : l'écriture est asynchrone, best-effort,
   et une panne Postgres ou une file pleine est journalisée puis ignorée.

Les coordonnées sont arrondies sur une grille d'environ **50 m**. Les features
sont stockées telles quelles ; aucune adresse client brute n'est conservée.

---

## 2. Activer en local avec Docker

Ajoute dans `.env` :

```dotenv
FIELD_COLLECTION_ENABLED=true
FIELD_COLLECTION_REQUIRE_CONSENT=true
```

Puis recrée le backend :

```powershell
docker compose up -d --build backend
```

Vérifie que le conteneur a bien pris la variable :

```powershell
docker exec delivery_backend printenv FIELD_COLLECTION_ENABLED
```

Attendu : `true`.

### Activer sans Docker (mvn)

```powershell
$env:FIELD_COLLECTION_ENABLED="true"
$env:FIELD_COLLECTION_REQUIRE_CONSENT="true"
.\mvnw.cmd spring-boot:run
```

### Sur Render

Backend service → **Environment** → ajouter :

```text
FIELD_COLLECTION_ENABLED=true
FIELD_COLLECTION_REQUIRE_CONSENT=true
```

Puis redéployer. Pour la démo publique, laisse `false` : la collecte ne doit
pas écrire de traces pendant que le jury clique.

Les valeurs par défaut sont dans `backend/src/main/resources/application.yml` :

```yaml
application:
  field-collection:
    enabled: ${FIELD_COLLECTION_ENABLED:false}
    require-consent: ${FIELD_COLLECTION_REQUIRE_CONSENT:true}
    retention-days: ${FIELD_COLLECTION_RETENTION_DAYS:90}
    target-runs: ${FIELD_COLLECTION_TARGET_RUNS:150}
```

---

## 3. Consentement

Les comptes de démonstration `courier1@swift.com` et `courier2@swift.com` sont
automatiquement consentants, parce que ce sont des comptes fictifs.

Pour un vrai coursier, le consentement est **opt-in**. Sans lui, aucune trace
n'est écrite. Exemple :

```sql
update users
set field_data_consent = true
where email = 'courier1@swift.com';
```

Pour retirer le consentement :

```sql
update users
set field_data_consent = false
where email = 'courier1@swift.com';
```

Le consentement est enregistré avec chaque run via `consent_version`
(`field-v1` par défaut).

---

## 4. Faire un run terrain

1. Connecte le coursier consentant et assigne-lui une livraison.
2. Passe la livraison en `IN_TRANSIT`.
3. Utilise le **vrai GPS** du téléphone. Le simulateur envoie
   `source=SIMULATED` : la trace est stockée mais **ne compte jamais** vers les
   150 runs.
4. Termine la livraison normalement.
5. Ne change pas de mode GPS au milieu d'un run : le store refuse de mélanger
   `FIELD` et `SIMULATED` dans le même run.

> Pour un test de démonstration, tu peux utiliser le simulateur : il apparaîtra
> avec `source=SIMULATED` et ne sera pas compté. C'est volontaire.

---

## 5. Voir la progression

```powershell
Get-Content scripts\field_collection_stats.sql | docker exec -i delivery_postgres psql -U admin -d delivery_db
```

Ou directement :

```powershell
docker exec delivery_postgres psql -U admin -d delivery_db -c "select count(*) as field_runs from field_runs where source='FIELD';"
```

La requête de progression compte :

- `source = 'FIELD'` ;
- `label is not null` ;
- `label <> 'EXCLUDE'`.

Objectif : `150`.

Lister les runs à labelliser :

```powershell
Get-Content scripts\field_collection_list.sql | docker exec -i delivery_postgres psql -U admin -d delivery_db
```

---

## 6. Labelliser un run

Récupère l'ID admin :

```powershell
docker exec delivery_postgres psql -U admin -d delivery_db -c "select id from users where email='admin@swift.com';"
```

Puis :

```powershell
docker cp scripts/field_collection_label.sql delivery_postgres:/tmp/
docker exec delivery_postgres psql -U admin -d delivery_db -v delivery_id="<uuid-livraison>" -v label="HONEST" -v admin_id="<uuid-admin>" -f /tmp/field_collection_label.sql
```

Labels acceptés : `HONEST`, `TELEPORT`, `SPRINT`, `DRIFT_THEN_VANISH`, `LOOP`,
`EXCLUDE`.

Règles de labellisation :

- `HONEST` : trajet plausible, livraison terminée normalement.
- `TELEPORT` : saut de position physiquement impossible.
- `SPRINT` : vitesse anormale sur une courte portion.
- `DRIFT_THEN_VANISH` : le coursier s'éloigne puis ne progresse plus.
- `LOOP` : tourne en rond sans progresser vers la cible.
- `EXCLUDE` : GPS perdu, run interrompu, donnée inutilisable.

---

## 7. Rétention

- Durée par défaut : **90 jours** (`FIELD_COLLECTION_RETENTION_DAYS`).
- Purge automatique tous les jours à **03:00** (`FieldRetentionJob`).
- Purge manuelle :

```sql
delete from field_trace_points
where run_id in (select id from field_runs where retention_until < now());

delete from field_runs where retention_until < now();
```

---

## 8. Désactiver

```dotenv
FIELD_COLLECTION_ENABLED=false
```

Puis :

```powershell
docker compose up -d backend
```

Aucune trace n'est écrite tant que l'interrupteur est faux.

---

## 9. Tests

```powershell
cd backend
.\mvnw.cmd "-Dtest=FieldTraceRecorderTest,FieldTraceStoreTest,FieldRetentionJobTest" test
```

Les tests couvrent :

- collecte désactivée → aucune écriture ;
- absence de consentement → aucune écriture ;
- consentement + `FIELD` → écriture avec les 8 features ;
- `SIMULATED` conservé avec sa source, jamais promu en `FIELD` ;
- file pleine → le broadcast GPS n'échoue pas ;
- arrondi des coordonnées ;
- purge des points avant les runs.

---

## 10. Ce qui reste pour #5b

- Collecter **150 runs réels** consentis.
- Labelliser les runs avec la grille ci-dessus.
- Exporter les features vers un CSV/JSON d'entraînement.
- Réentraîner le Random Forest sur réel + synthétique.
- Évaluer sur le holdout bruité et comparer au modèle actuel.
- Recalibrer le seuil et la règle des 3 fixes.

Tant que ces étapes ne sont pas faites, la performance terrain reste une
hypothèse, pas une preuve.
