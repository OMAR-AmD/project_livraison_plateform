# Session 3 — Rapport d'avancement (brouillon local)

> **Projet :** SwiftDeliver — plateforme de livraison avec suivi, preuve de remise et détection de fraude.
> **Date :** 3 novembre 2026
> **Auteur :** [ton nom]
> **Repo local :** `plateforme-livraison-feature-ui-fixes-and-revenue-logic`
> **Branche :** `main`
> **Dernier commit avant ce rapport :** `659e736 docs: confirm-step, demo password, OSRM fallback, measurements, honest counts`

---

## 1. Discussion avec le Software Engineer coach

> **À compléter avec tes notes réelles.** Je ne peux pas inventer ce que le coach a dit. Garde des phrases courtes, factuelles, et cite un retour technique précis.

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
- [ ] Démarrer la collecte #5 (0/150).
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
| #5 Field collection (passive) | In Progress — 0/150 | aucune donnée réelle labellisée |

Le backlog contient les sous-tâches, les critères d'acceptation, les limites assumées et les champs à recréer dans GitHub Projects.

### Phrase pour le rapport

> « Le board GitHub peut encore afficher #1 et #2 en cours, mais les deux sont déjà implémentés et testés dans le code. Le seul ticket réellement ouvert est #5 : la collecte terrain est à 0/150 runs réels. »

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

git log --oneline --decorate -8
```

> **Ne pas push.** Le checklist demande des commits locaux uniquement.

### Sortie attendue de `git log`

```text
[hash] docs(session3): add sprint backlog, baseline report and handoff
733a8f1 chore(git): ignore local baseline artefacts
534b8eb test(security): make verify_chain idempotent and align postgres container
659e736 docs: confirm-step, demo password, OSRM fallback, measurements, honest counts
6eb52ab feat: demo password from env, OSRM fallback pricing, two-step cancellation
30f86a6 docs: chain shipped, secrets hardened, laptop-first model, honest counts
```

Remplace `[hash]` par les hashes réels après le commit.

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

### Ce qui est fait

- Le baseline ML tourne localement, avec un code de sortie 0.
- Le backlog Sprint 1 est rédigé avec des critères d'acceptation.
- Les commits locaux sont prêts (ou faits) sans push.
- Le rapport contient les preuves nécessaires.

### Ce qui reste ouvert

- **#5 : 0/150 runs réels.** C'est la limite principale du projet.
- **Render PostgreSQL :** l'échéance gratuite était le **2 novembre 2026**. Nous sommes le **3 novembre 2026** : vérifier immédiatement si la base a expiré, faire un `pg_dump` si elle est encore accessible, et renouveler ou migrer.
- **Render 502 :** le backend peut être endormi ou en erreur. Vérifier `/api/v1/health` et les logs Render avant la démo.
- **Capture d'écran du coach :** la section 1 doit être remplie avec le retour réel, pas inventé.

### Prochaine action

1. Remplir la section 1 avec les notes réelles du coach.
2. Prendre les trois captures.
3. Coller ce rapport dans le Class Notebook.
4. Transférer `SPRINT_1_BACKLOG.md` dans GitHub Projects.
5. Vérifier la base Render avant toute démo.

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
