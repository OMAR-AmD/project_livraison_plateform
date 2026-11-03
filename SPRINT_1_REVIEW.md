# Sprint 1 — Review de clôture

> **Projet :** SwiftDeliver
> **Date de clôture :** 3 novembre 2026
> **Périmètre :** Sprint 1 du backlog #1/#2/#5 (sécurité de la remise + préparation de la collecte terrain)
> **Branche :** `main`
> **HEAD :** `21c0891` + commit de clôture `docs(sprint1): close sprint with #1/#2 done and #5 replanned`
> **Statut :** Sprint 1 clôturé ; #1/#2 `Done` ; #5 replanifié au Sprint 2.

---

## 1. Objectif du sprint

Le backlog définit trois volets :

1. sécuriser la remise (plafonner les tentatives de code) ;
2. rendre la preuve de livraison vérifiable (chaîne de scellés + `verifyChain`) ;
3. préparer la collecte de données terrain (#5).

## 2. Ce qui est livré

| Ticket | Statut | Preuve |
|---|---|---|
| #1 Attempt caps on handover codes | `Done` | commit `0c6620d`, tests du limiteur |
| #2 Seal chaining + `verifyChain()` | `Done` | commits `563eaee`, `c67303e`, script `verify_chain.js` |
| #5 Field collection (passive) | Non terminé — replanifié Sprint 2 | `0/150` runs réels |

## 3. Ce qui n'est pas livré

#5 n'est pas terminé. Aucun run réel n'a été collecté ni labellisé. Le modèle livré reste entraîné sur des données **synthétiques** ; le holdout bruité donne des chiffres honnêtes, mais il ne remplace pas 150 runs terrain.

Le ticket #5 demandait : schéma durable, collecte passive, consentement/rétention, outil de labellisation, 150 runs, réentraînement v2, évaluation holdout, calibration. C'est un travail de collecte terrain, pas un livrable de code d'un sprint court.

## 4. Décision de clôture

- Le Sprint 1 est clôturé sur les deux barrières de sécurité : #1 et #2.
- #5 est retiré du Sprint 1 et replanifié au Sprint 2, découpé en :
  - **#5a — Field collection readiness** : schéma, recorder passif, consentement/rétention, CLI de labellisation, export, tests, runbook.
  - **#5b — 150 runs + modèle v2** : collecte réelle, labellisation, réentraînement, holdout bruité, calibration.
- #5 n'est **pas** marqué `Done`. Aucun run simulé n'est compté comme run terrain.
- GitHub : les issues #1 et #2 sont déjà fermées ; #5 est ouverte. Il reste à mettre à jour l'itération Projects (#1/#2 en `Done`, #5 en `Todo (Sprint 2)`) puis à fermer l'itération Sprint 1.

## 5. Preuves

- **Tests backend :** 183/183 dans les rapports Surefire (28 classes, 0 échec, dernier run le 14 octobre 2026). À rejouer avec `./mvnw test` si l'environnement Docker est disponible.
- **Commits :** `0c6620d` (#1), `563eaee` + `c67303e` (#2), durcissements `2b91ac4`, `afdc3c5`.
- **Script live :** `frontend/verify_chain.js` (13/13 quand la stack locale tourne).
- **Issues GitHub :** [#1](https://github.com/OMAR-AmD/project_livraison_plateform/issues/1) fermée, [#2](https://github.com/OMAR-AmD/project_livraison_plateform/issues/2) fermée, [#5](https://github.com/OMAR-AmD/project_livraison_plateform/issues/5) ouverte.
- **Backlog :** `SPRINT_1_BACKLOG.md`.
- **Rapport de session :** `SESSION_3_REPORT.md`.

## 6. Risques et limites à la clôture

- **Backend Render :** au moment de la clôture, le frontend répond `200` mais le backend ne répond pas (timeout/reset ; le proxy `/api/v1/health` du frontend aussi). Cause probable : expiration de la Postgres gratuite le 2 novembre 2026. À vérifier dans le dashboard ; prévoir la stack locale en fallback.
- **Board GitHub :** les issues #1/#2 sont déjà fermées, mais l'itération Projects peut être en retard. Elle n'a pas pu être mise à jour depuis cette session (`gh` non authentifié) ; appliquer les actions de la section 4 manuellement.
- **#5 :** `0/150` runs réels ; la performance terrain reste une hypothèse, pas une preuve.
- **Modèle :** données synthétiques ; le motif `loop` reste mal détecté (0,003).
- **Coach :** la section 1 de `SESSION_3_REPORT.md` doit être remplie avec les notes réelles, sans invention.

## 7. Phrase de soutenance

> « Le Sprint 1 est clôturé sur les deux barrières de sécurité : #1 et #2 sont livrés et prouvés. #5, la collecte de 150 runs réels, est un travail de données qui dépasse le sprint : il est replanifié au Sprint 2, avec un compteur honnête à 0/150. »

## 8. Prochaine étape

1. Mettre l'itération Projects à jour (issues #1/#2 déjà fermées, #5 à déplacer vers le Sprint 2).
2. Démarrer le Sprint 2 par #5a : schéma, recorder passif, consentement/rétention, CLI de labellisation, export.
3. Puis #5b : collecter 150 runs réels, labelliser, réentraîner, évaluer sur le holdout bruité, recalibrer.
