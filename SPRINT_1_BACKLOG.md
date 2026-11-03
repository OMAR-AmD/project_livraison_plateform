# Sprint 1 — Backlog granulaire

> Projet : **SwiftDeliver**
> But du Sprint 1 : sécuriser la remise, rendre la preuve de livraison vérifiable, puis préparer la collecte de données terrain.
> Statut global : **#1 et #2 terminés dans le code ; #5 en cours (0/150 runs réels).**
> Ce fichier est la version locale à transférer dans GitHub Projects quand la connexion sera rétablie.

---

## Légende

- `[x]` fait et vérifié
- `[ ]` à faire
- `[~]` en cours
- `Done` = code + test + preuve de commit
- `In Progress` = travail commencé, critère d'acceptation non atteint

---

## Ticket #1 — Plafonner les tentatives de code de remise

**Titre GitHub :** `[Sprint 1] Attempt caps on handover codes`
**Statut :** `Done`
**Priorité :** haute
**Labels :** `security`, `backend`, `redis`, `sprint-1`
**Commit de référence :** `0c6620d`

### Objectif

Empêcher le brute-force du code de remise à la porte. Un client ne doit pas pouvoir essayer des milliers de combinaisons pour valider une livraison à la place du coursier.

### Sous-tâches

- [x] Définir le contrat `HandoverCodeRateLimiter` et le point d'application dans le flux de remise.
- [x] Stocker le compteur dans Redis avec une fenêtre de 15 minutes.
- [x] Incrémenter le compteur à chaque tentative échouée, sans le faire baisser sur une tentative réussie.
- [x] Retourner `429 Too Many Requests` avec un message explicite quand le plafond est atteint.
- [x] Fail-closed : si Redis est indisponible, refuser la tentative plutôt que laisser passer.
- [x] Ajouter les tests unitaires et d'intégration du limiteur.
- [x] Documenter la limite et le comportement attendu.

### Critères d'acceptation

- [x] Après le nombre maximal de tentatives, le endpoint répond `429`.
- [x] Le compteur expire après la fenêtre configurée.
- [x] Une panne Redis ne désactive pas silencieusement la protection.
- [x] Le code de remise valide n'est jamais journalisé en clair.
- [x] Le test couvre le cas nominal, le dépassement et la panne Redis.

### Preuve

- Commit : `0c6620d`
- Fichiers principaux : `HandoverCodeRateLimiter`, tests associés, configuration Redis.

### Limite assumée

Le plafond ralentit le brute-force, il ne le rend pas impossible si l'attaquant distribue les tentatives sur plusieurs comptes ou plusieurs instances. La protection est locale au déploiement et repose sur Redis.

---

## Ticket #2 — Chaîner les scellés et exposer `verifyChain()`

**Titre GitHub :** `[Sprint 1] Seal chaining + verifyChain()`
**Statut :** `Done`
**Priorité :** haute
**Labels :** `security`, `backend`, `audit`, `sprint-1`
**Commits de référence :** `563eaee`, `c67303e`

### Objectif

Ne plus se contenter d'un HMAC par livraison : relier les scellés entre eux pour qu'une suppression ou une modification en milieu de chaîne soit détectable. Exposer une vérification lisible par l'admin et par le script de démonstration.

### Sous-tâches

- [x] Passer le payload de scellé en v2 avec `seal_seq`, `prev_hash` et `proof_hash`.
- [x] Ajouter l'entité et le repository de tête de chaîne (`SealChainHead`).
- [x] Sérialiser les scellés concurrents pour éviter les fourches.
- [x] Ordonner la vérification par `seal_seq`, pas par date.
- [x] Écrire un tombstone lors d'une suppression pour que la chaîne reste vérifiable.
- [x] Backfiller les livraisons existantes sans casser les preuves déjà émises.
- [x] Exposer `GET /api/v1/admin/deliveries/proof-chain`.
- [x] Brancher le script live `frontend/verify_chain.js` sur le endpoint.
- [x] Ajouter les tests de chaîne, de concurrence et de suppression.
- [x] Ajouter un test de mutation pour vérifier que le test échoue si la sauvegarde de tête est retirée.

### Critères d'acceptation

- [x] Deux scellés successifs se vérifient comme une chaîne.
- [x] Des scellés concurrents forment une seule ligne, pas une fourche.
- [x] Une suppression via l'API laisse un tombstone et la chaîne reste verte.
- [x] Une suppression SQL au milieu de la chaîne est détectée au successeur.
- [x] La vérification est idempotente : elle peut tourner sur une base qui contient déjà des scellés.
- [x] Le script `verify_chain.js` passe sur une base propre.

### Preuve

- Commits : `563eaee`, `c67303e`
- Script live : `frontend/verify_chain.js`
- Endpoint : `GET /api/v1/admin/deliveries/proof-chain`

### Limite assumée

La suppression de la **queue** de chaîne reste le gap connu : une chaîne plus courte peut encore vérifier tant que l'ancre de tête n'est pas exploitée pour comparer la longueur attendue. C'est le prochain durcissement à prévoir, pas un acquis.

---

## Ticket #5 — Collecte terrain passive

**Titre GitHub :** `[Sprint 1] Field collection (passive)`
**Statut :** `In Progress` — **0/150 runs réels labellisés**
**Priorité :** haute
**Labels :** `ml`, `data`, `field`, `sprint-1`
**Dépendance :** #2 (traces et preuves fiables)

### Objectif

Sortir des données synthétiques. Collecter passivement des traces GPS réelles, les labelliser honnêtement, puis réentraîner le modèle de fraude sur ces données.

### Sous-tâches

- [ ] Définir le schéma de trace durable : `delivery_id`, `courier_id`, `timestamp`, `lat`, `lng`, features calculées, label.
- [ ] Ajouter la collecte passive sur les mises à jour GPS, sans ralentir le flux temps réel.
- [ ] Définir la procédure de consentement et de rétention des données.
- [ ] Créer l'outil de labellisation : `honest`, `teleport`, `sprint`, `drift_then_vanish`, `loop`.
- [ ] Collecter **150 runs réels labellisés** (objectif : 0/150).
- [ ] Réentraîner le Random Forest sur les données réelles + synthétiques.
- [ ] Évaluer sur le holdout bruité, pas seulement sur le simulateur.
- [ ] Recalibrer le seuil et la règle des 3 fixes consécutifs.
- [ ] Documenter les métriques, les limites et le taux de faux positifs.

### Critères d'acceptation

- [ ] 150 runs réels labellisés et stockés durablement.
- [ ] Le pipeline de réentraînement est reproductible en une commande.
- [ ] Le modèle v2 est comparé au modèle synthétique sur le même holdout.
- [ ] Le taux de faux positifs par run honnête est mesuré et documenté.
- [ ] Aucun run réel n'est utilisé sans consentement et sans politique de rétention.

### Pourquoi c'est le vrai ticket de Sprint 1

Le modèle actuel est entraîné sur des données **synthétiques**. Le holdout bruité donne des chiffres honnêtes, mais il ne remplace pas 150 runs réels. Tant que #5 est à `0/150`, la performance terrain reste une hypothèse, pas une preuve.

---

## Ce qui n'est pas dans le Sprint 1

Ces sujets sont importants, mais ils ne doivent pas diluer le sprint :

- Ancre de tête complète pour fermer la suppression de queue.
- QR tournant / code à usage unique.
- Refresh tokens et rotation de session.
- CI complète avec tests de mutation sur tous les modules.
- Migration vers des microservices.
- Dashboard temps réel avancé.

---

## Champs à recréer dans GitHub Projects

| Champ | Valeur |
|---|---|
| Title | Titre du ticket |
| Description | Objectif + contexte + limite |
| Status | `Done` / `In Progress` / `Todo` |
| Labels | `security`, `backend`, `ml`, `sprint-1` |
| Estimate | 1 à 5 points |
| Acceptance criteria | Cases à cocher ci-dessus |
| Evidence | Commit, test, capture ou sortie terminal |

### Ordre de transfert conseillé

1. Créer les trois items avec le titre exact.
2. Coller les sous-tâches dans la description.
3. Mettre #1 et #2 en `Done` avec les hashes de commit.
4. Mettre #5 en `In Progress` avec `0/150`.
5. Ajouter un commentaire sur #5 : « données synthétiques uniquement pour l'instant ; collecte réelle à démarrer ».

---

## Preuve de statut pour la soutenance

| Ticket | Statut réel | Preuve |
|---|---|---|
| #1 Attempt caps | Done | `0c6620d`, tests du limiteur |
| #2 Seal chaining | Done | `563eaee`, `c67303e`, `verify_chain.js` |
| #5 Field collection | In Progress | `0/150` runs réels, modèle encore synthétique |

**Phrase honnête :** « Le board peut encore afficher #1 et #2 en cours, mais le code et les tests sont déjà là. Le seul ticket réellement ouvert est #5, et il l'est pour une bonne raison : nous n'avons pas encore 150 runs terrain. »
