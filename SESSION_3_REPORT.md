# Session 3 — Rapport d'avancement (brouillon local)

> **Projet :** SwiftDeliver — plateforme de livraison avec suivi, preuve de remise et détection de fraude.
> **Date :** 3 novembre 2026
> **Auteur :** [ton nom]
> **Repo local :** `plateforme-livraison-feature-ui-fixes-and-revenue-logic`
> **Branche :** `main`
> **Dernier commit avant ce rapport :** `659e736 docs: confirm-step, demo password, OSRM fallback, measurements, honest counts`
> **HEAD au moment de la clôture du Sprint 1 :** `21c0891` + commit de clôture `docs(sprint1): close sprint with #1/#2 done and #5 replanned`

---

## 1. Discussion avec le Software Engineer coach

> **À compléter avec tes notes réelles.** Je ne peux pas inventer ce que le coach a dit. Garde des phrases courtes, factuelles, et cite un retour technique précis.

> **Décision de clôture (3 novembre 2026) :** #1 et #2 sont `Done` ; #5 n'est pas terminé (`0/150`) et est replanifié au Sprint 2. Voir la section 6 et `SPRINT_1_REVIEW.md`.

### Sujet abordé

- [ ] Revue du baseline ML et de la reproductibilité.
- [ ] Revue du backlog Sprint 1 et de la granularité des tickets.
- [ ] Revue de l'hygiène Git (commits locaux, messages, absence de push).
- [ ] Autre : ______________________________________________

### Retour technique reçu

- [ ] Le baseline doit tourner **sur la machine**, pas seulement dans un notebook.
- [ ] Le backlog doit être découpé en sous-tâches vérifiables, avec critères d'acceptation.
- [ ] Les commits doivent être petits, descriptifs et séparés par intention.
- [ ] Les preuves doivent être visuelles : terminal, `git log`, backlog.
- [ ] Autre : ______________________________________________

### Décision prise

- [ ] Exécuter `ml/train_trajectory_anomaly.py` localement et conserver la sortie.
- [ ] Créer `SPRINT_1_BACKLOG.md` et le transférer dans GitHub Projects.
- [ ] Commiter localement sans push.
- [ ] Prendre les captures demandées.

### Action suivante

- [ ] Transférer les trois tickets dans GitHub Projects.
- [ ] Démarrer le Sprint 2 : pipeline #5a, puis collecte #5b (0/150).
- [ ] Vérifier l'expiration Render PostgreSQL.

---

## 2. « Hello World » : exécution du baseline ML

### Commande exécutée

```powershell
python ml/train_trajectory_anomaly.py
```

### Environnement

| Élément | Version |
|---|---|
| Python | 3.11.9 |
| numpy | 2.4.6 |
| scikit-learn | 1.9.1 |
| scipy | 1.17.1 |
| joblib | 1.6.0 |

### Résultat

**Code de sortie : `0`** — le script s'est exécuté jusqu'au bout et a écrit le modèle baseline.

Extrait de la sortie terminal :

```text
==========================================================================
Courier trajectory anomaly detector -- offline training
==========================================================================
Training data is SYNTHETIC (no real courier history exists yet).
Reported metrics are reproducible by re-running this script.

building training set...
  training (honest broadcasts only): 6934
  holdout: 8565 broadcasts across 180 runs, 654 of them dishonest

training Isolation Forest...

broadcast level
  tp=395 fn=259 fp=968 tn=6943
  precision=0.290  recall=0.604  f1=0.392
  honest broadcasts wrongly flagged: 968 of 7911 (12.24%)

  detection rate per anomaly kind (share of DISHONEST fixes caught):
    teleport               100.0%  (40 dishonest fixes)
    sprint                  99.5%  (200 dishonest fixes)
    drift_then_vanish       37.7%  (414 dishonest fixes)

run level (did the alert fire for this run at all?)
  fraud runs alerted:        120/120 (100%)
  honest runs left unflagged: 2/60 (3%)

baseline for comparison -- the fixed 500 m distance rule:
  broadcast precision=0.076 recall=1.000
  but 7904 of 7911 honest fixes sit over 500 m from the target --
  a courier on the far side of a 12 km run trips it constantly.
  run level: fraud alerted 120/120, honest runs noisily flagged 60/60

writing model...
  backend\src\main\resources\model\trajectory_anomaly_iforest.json  (319 KB, 100 trees)
  ml\trajectory_iforest_parity.json  (120 vectors)

done.
```

### Ce que ce résultat prouve

- Le pipeline ML tourne **hors notebook**, sur la machine.
- Le modèle baseline est exporté et un fichier de parité est généré.
- Les métriques sont reproductibles par une seule commande.

### Ce que ce résultat ne prouve pas

- Ce n'est **pas** le modèle Random Forest livré dans le backend. C'est le baseline Isolation Forest.
- Les données sont **synthétiques**. Les chiffres ne sont pas une performance terrain.
- Le baseline a un rappel correct sur `teleport` et `sprint`, mais il rate une grande partie de `drift_then_vanish` (37,7 %) et flag 12,24 % des fixes honnêtes au niveau broadcast.
- Le modèle livré (Random Forest) a ses propres chiffres sur le holdout bruité : précision 0,636, rappel 0,443, F1 0,522. Ne pas mélanger les deux tableaux.

### Artefacts générés

| Fichier | Rôle | Statut Git |
|---|---|---|
| `backend/src/main/resources/model/trajectory_anomaly_iforest.json` | Modèle baseline Isolation Forest | Non suivi |
| `ml/trajectory_iforest_parity.json` | Vecteurs de parité Python → Java | Non suivi |
| `SESSION_3_BASELINE_RUN.log` | Transcription de la sortie terminal | Ignoré par `*.log` |
| `SESSION_3_BASELINE_RUN.txt` | Copie texte pour preuve / commit | À committer |

> Les deux artefacts baseline ne sont pas utilisés par le backend en production. Le backend charge le Random Forest `trajectory_fraud_rf.json.gz`.

---

## 3. Backlog Sprint 1

Fichier local : `SPRINT_1_BACKLOG.md`

| Ticket | Statut | Preuve |
|---|---|---|
| #1 Attempt caps on handover codes | Done | `0c6620d` |
| #2 Seal chaining + `verifyChain()` | Done | `563eaee`, `c67303e` |
| #5 Field collection (passive) | Replanifié Sprint 2 — 0/150 | aucune donnée réelle labellisée ; voir `SPRINT_1_REVIEW.md` |

Le backlog contient les sous-tâches, les critères d'acceptation, les limites assumées et les champs à recréer dans GitHub Projects.

Les issues GitHub #1 et #2 sont déjà fermées ; #5 est ouverte et doit être déplacée vers le Sprint 2 dans Projects.

### Phrase pour le rapport

> « Le Sprint 1 est clôturé : #1 et #2 sont implémentés et testés. #5 n'est pas terminé — 0/150 runs réels — et il est replanifié au Sprint 2. Je préfère un ticket honnêtement replanifié à un ticket marqué Done sans données. »

---

## 4. Commits locaux

### Commandes

```powershell
git status
git add frontend/verify_chain.js
git commit -m "test(security): make verify_chain idempotent and align postgres container"

git add .gitignore
git commit -m "chore(git): ignore local baseline artefacts"

git add SPRINT_1_BACKLOG.md SESSION_3_REPORT.md CONVERSATION_EXPORT_2026-11-03.md SESSION_3_BASELINE_RUN.txt
git commit -m "docs(session3): add sprint backlog, baseline report and handoff"

git add SPRINT_1_BACKLOG.md SESSION_3_REPORT.md SPRINT_1_REVIEW.md CONVERSATION_EXPORT_2026-11-03.md SWIFTDELIVER_REPORT.md
git commit -m "docs(sprint1): close sprint with #1/#2 done and #5 replanned"

git log --oneline --decorate -8
```

> **Ne pas push.** Le checklist demande des commits locaux uniquement.

### Sortie attendue de `git log`

```text
<closure> docs(sprint1): close sprint with #1/#2 done and #5 replanned
21c0891 docs(session3): sync commit hashes in handoff
f982525 docs(session3): add sprint backlog, baseline report and handoff
733a8f1 chore(git): ignore local baseline artefacts
534b8eb test(security): make verify_chain idempotent and align postgres container
659e736 docs: confirm-step, demo password, OSRM fallback, measurements, honest counts
6eb52ab feat: demo password from env, OSRM fallback pricing, two-step cancellation
30f86a6 docs: chain shipped, secrets hardened, laptop-first model, honest counts
```

Le hash `<closure>` est celui du commit de clôture ; les autres sont ceux du dépôt local. Vérifie-les avec `git log --oneline --decorate -8`.

---

## 5. Captures d'écran à insérer dans le Class Notebook

### Capture 1 — Terminal qui exécute le baseline

```powershell
python ml/train_trajectory_anomaly.py
```

Prends la capture **après** la ligne `done.` et avant que la fenêtre ne se ferme. On doit voir la commande, les métriques et `done.`.

Alternative si le terminal est déjà fermé :

```powershell
Get-Content SESSION_3_BASELINE_RUN.log
```

### Capture 2 — Historique Git local

```powershell
git log --oneline --decorate -8
```

On doit voir les commits de la session au-dessus de `659e736`.

### Capture 3 — Backlog Sprint 1

Ouvre `SPRINT_1_BACKLOG.md` dans VS Code (ou affiche-le dans le terminal) et capture les trois tickets avec leurs statuts et leurs sous-tâches.

### Capture 4 — Optionnelle : `git status`

```powershell
git status --short --branch
```

Utile pour prouver que le travail est local et que rien n'a été poussé.

---

## 6. Bilan honnête de la session

### Clôture du Sprint 1 (3 novembre 2026)

- **#1** et **#2** sont `Done`.
- **#5** n'est pas terminé : `0/150` runs réels. Il est replanifié au Sprint 2.
- **Décision :** ne pas marquer #5 `Done` ; la collecte terrain est un travail de données, pas un livrable de code. Le Sprint 1 est clôturé sur les deux barrières de sécurité.
- **Artefact de clôture :** `SPRINT_1_REVIEW.md`.

### Ce qui est fait

- Le Sprint 1 est clôturé : #1 et #2 sont `Done`.
- Le baseline ML tourne localement, avec un code de sortie 0.
- Le backlog Sprint 1 est rédigé avec des critères d'acceptation et une décision de clôture.
- Les commits locaux sont prêts (ou faits) sans push.
- Le rapport contient les preuves nécessaires.

### Ce qui reste ouvert

- **#5 : 0/150 runs réels.** C'est la limite principale du projet. Il est replanifié au Sprint 2 (#5a pipeline, #5b collecte + modèle v2).
- **Render PostgreSQL :** l'échéance gratuite était le **2 novembre 2026**. Nous sommes le **3 novembre 2026** : vérifier immédiatement si la base a expiré, faire un `pg_dump` si elle est encore accessible, et renouveler ou migrer.
- **Backend Render :** au moment de la clôture, le frontend répond `200` mais le backend ne répond pas (timeout/reset ; le proxy `/api/v1/health` du frontend aussi). Cause probable : expiration de la Postgres gratuite le 2 novembre 2026. Vérifier le dashboard et prévoir la stack locale en fallback.
- **Capture d'écran du coach :** la section 1 doit être remplie avec le retour réel, pas inventé.

### Prochaine action

1. Mettre l'itération Projects à jour : #1/#2 en `Done` (issues déjà fermées), #5 en `Todo (Sprint 2)`, puis fermer l'itération Sprint 1.
2. Remplir la section 1 avec les notes réelles du coach.
3. Prendre les trois captures (terminal, `git log`, backlog).
4. Coller ce rapport et `SPRINT_1_REVIEW.md` dans le Class Notebook.
5. Vérifier la base Render et prévoir la stack locale en fallback pour la démo.
6. Démarrer le Sprint 2 par #5a (pipeline de collecte), puis #5b (150 runs + modèle v2).

---

## 7. Annexe — commandes utiles

```powershell
# Se placer dans le repo
Set-Location "C:\Users\MSI\Desktop\plateforme-livraison-feature-ui-fixes-and-revenue-logic\plateforme-livraison-feature-ui-fixes-and-revenue-logic"

# Vérifier l'environnement Python
python --version
python -c "import sklearn, numpy; print('sklearn', sklearn.__version__, 'numpy', numpy.__version__)"

# Rejouer le baseline
python ml/train_trajectory_anomaly.py

# Voir les commits
git log --oneline --decorate -8

# Voir l'état sans push
git status --short --branch

# Vérifier que le backend Render répond
Invoke-RestMethod "https://swiftdeliver-backend-j47m.onrender.com/api/v1/health"
```
