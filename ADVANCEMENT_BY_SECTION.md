# SwiftDeliver — Advancement by Section

A section-by-section mapping of the **framing document**
(`Project Scoping & Innovation Roadmap_Canvas_SwiftDeliver`) to what the code
actually delivers today.

Each section below shows:

- **Framing document** — what the submitted document states or commits to.
- **Advancement** — what has been delivered, with evidence.
- **NEW** marks work completed *after* the framing document (the 2026-10-13 batch,
  commits `f29167c`, `dc1de83`, `479b4b6`, `59a9155`).

Repository: branch `main`, code baseline `59a9155`, in sync with `origin/main`.
Backend tests: **91 / 91 green**. Playwright verifiers + 15-check rehearsal: green.

---

## 1. Project Identity & Repository

**Framing document.** Project SwiftDeliver; student Amdouni Omar; ex-teammates
Benramdane Kamal, Akby Anass; repository on branch `main`.

**Advancement**
- Repository finalised and public: `https://github.com/OMAR-AmD/project_livraison_plateform`, branch `main`.
- `main` in sync with `origin/main`; working tree clean; code baseline `59a9155`.
- Full documentation set in the repo: `README.md`, `SYSTEM_ARCHITECTURE.md`,
  `API_REFERENCE.md`, `DEMO.md`, `DEPLOY.md`, `SWIFTDELIVER_REPORT.md`.
- **NEW** — `PROJECT_OVERVIEW.md` added: the whole platform in one self-contained document.
- CI (`.github/workflows/ci.yml`) and the Render blueprint (`render.yaml`) are wired.

---

## 2. Existing Baseline Summary

**Framing document.** Last-mile delivery platform (Next.js + Spring Boot): clients
book, pay and track; couriers deliver with live GPS; admins supervise. OSRM
road-route pricing; HMAC-sealed proofs. Runs locally (Docker Compose) and
publicly (Render, Frankfurt); the RAG chat needs local Ollama.

**Advancement**
- Local stack: `docker compose up` brings up **5 healthy services**
  (`postgres`, `redis`, `osrm`, `backend`, `frontend`).
- Public deployment: Render Frankfurt blueprint; booking, tracking and fraud
  detection run fully, and the RAG chat answers `unavailable` where no Ollama is
  present — by design, not by failure.
- **NEW** — the two AI claims the baseline relied on are now *enforced in the
  product*: the k=3 run rule (see §6), the handover-code throttle (see §8), and
  `ARRIVED` counted correctly in stats (see §10).

---

## 3. Target Audience & Potential Client

**Framing document.** Primary buyer: a Casablanca dispatcher/admin settling
"delivered / not delivered" disputes. End-users: e-commerce shops, recipients,
couriers.

**Advancement**
- No code change required — the persona shaped the design decisions:
  **human-review flags, never auto-punished**; a verifiable proof the dispatcher
  can check; a live parcel view for recipients.
- The decision-support stance is explicit on screen (the fraud modal states the
  score is a review signal, not proof of wrongdoing).

---

## 4. Problem Statement & Client Need

**Framing document.** Manual dispatch loses ~231 s/round vs optimal; false
"delivered" claims can't be disproved; implausible GPS goes unnoticed (a speed
rule catches only 35.8% of stall-then-vanish runs, but only ~10% of teleports —
one threshold, two patterns); support drowns with nothing verifiable to check.

**Advancement**
- The measured baselines are reproducible: `ml/benchmark_dispatch.py` +
  `ml/dispatch_benchmark.json` (dispatch), `ml/evaluate_noisy.py` +
  `ml/noisy_eval_report.json` (fraud).
- The fixes these numbers motivate are shipped and wired:
  - dispatch → **OR-Tools VRP at payment capture** (§9);
  - "delivered" disputes → **HMAC-sealed proof of delivery** (§8);
  - implausible GPS → **ML-scored fixes with a run-level alert** (§6);
  - support load → **RAG assistant** (§6).

---

## 5. Innovation Vision (S5)

**Framing document.**
- **What:** automatic OSRM pricing and dispatch, ML-scored GPS fixes with
  human-review alerts, HMAC proofs closable by GPS or single-use 6-digit code,
  RAG assistant over FAQ plus live orders with cancel.
- **Why:** cost, assignment, score and proof computed and checkable; support
  deflected.

**Advancement**
- **What — delivered:** server-truth pricing from real OSRM routes; OR-Tools VRP
  dispatch; per-fix ML scoring; HMAC proofs sealable by GPS (≤ 500 m) or the
  single-use 6-digit code; RAG assistant grounded in FAQ + live orders with an
  ownership-checked cancel.
- **NEW** — the rule behind "human-review alerts" is now enforced in-product by a
  single shared `RunRule`, so the live alert and the dashboard badge cannot
  disagree.
- **Why — delivered:** cost, assignment, score and proof are all computed and
  independently checkable; routine support is deflected by the assistant.

---

## 6. Artificial Intelligence Component

**Framing document.** Primary: supervised Random Forest per GPS broadcast
(0.780/0.896 vs 0.585/0.358 speed rule); noisy validation 0.636/0.443 within the
2% budget. *Shipped rule (k=3 consecutive or one fix ≥ 0.97)*: 29/400 honest runs
flagged (7.25%), fraud recall 71/98. Honest limits disclosed; Isolation Forest
rejected. Secondary: RAG with ownership-checked tools.

**Advancement**
| Point | Detail |
| :--- | :--- |
| Model | 60-tree Random Forest, threshold **0.82**; `TrajectoryFraudModel` loads once at startup and **refuses to start** if the artifact is missing or unparsable. |
| Parity | Java ↔ Python worst diff **2.2e-16**, pinned by `TrajectoryFraudModelTest`. |
| **NEW** Run rule | `RunRule.java` — **k=3 consecutive flagged fixes OR one fix ≥ 0.97** — is the **single source of truth**, called by both `TrajectoryFraudService` (streaming alert) and `FraudTrailStore.Trail.flagged()` (batch badge). |
| **NEW** Correction | The badge previously fired on **a single flagged fix** (`flaggedFixes > 0`), which lit **43 % of honest runs**. It now applies the run rule. |
| **NEW** Tests | `RunRuleTest` (9) plus run-rule cases in `FraudTrailStoreTest`. |
| Numbers (retained) | 0.780/0.896 vs 0.585/0.358 (the speed rule is 35.8% on stall-then-vanish but only ~10% on teleports — one threshold, two patterns); noisy 0.636/0.443 inside the 2 % budget; run rule 29/400 honest (7.25 %), recall 71/98 (teleport 23/25, sprint 23/25, drift 24/25, loop 1/23). |
| Honest limits | Synthetic training data (disclosed in the UI); the loop blind spot stated openly; the toggle rule pre-committed. |
| Rejected alternative | Isolation Forest built, measured and rejected (`ml/supervised_vs_unsupervised.py`). |
| Secondary | RAG assistant with ownership-checked cancel/details tools. |

---

## 7. Local AI Stack (Excluding APIs)

**Framing document.** Scikit-learn Random Forest trained offline, exported as
gzipped JSON, scored in-process in Java. OR-Tools VRP, OSRM times/distances.
Ollama `llama3.1:8b` + `nomic-embed-text`, pgvector FAQ. No black-box API.

**Advancement**
- Shipped artefact: `backend/src/main/resources/trajectory_fraud_rf.json.gz`,
  evaluated in-process by Java.
- OR-Tools VRP fed by an OSRM `/table` matrix; a per-delivery `isServed()` guard
  ensures a dropped stop cannot be silently assigned.
- Ollama `llama3.1:8b` (chat) + `nomic-embed-text` (embeddings); pgvector store
  (HNSW, cosine, 768 dims); the FAQ corpus is rebuilt on every startup.
- **No black-box API anywhere in the request path** — routing, embeddings and the
  LLM all run locally or in-process.

---

## 8. Second Engineering Domain (Cybersecurity)

**Framing document.** Cybersecurity. ML-based abnormal-behaviour detection
(UEBA-like) + tamper-evident HMAC proofs. HMAC, JWT/RBAC, fixed-window rate
limiting (429 + Retry-After), tokenVersion logout revocation, Redis trails.
*(The original canvas said brute force on the 6-digit code was "unthrottled —
capped in November"; the canvas now records the partial delivery and the
roadmap's Nov W1–W2 bar for what remains.)*

**Advancement**
- **NEW** — the 6-digit handover code is **now brute-force throttled**:
  `HandoverCodeRateLimiter` permits **5 failures per delivery** and
  **20 per courier** per 15-minute window, answered `429` + `Retry-After`; a
  correct code clears the delivery counter. It is **in memory and per instance**
  (counters reset on restart).
  Tests: `HandoverCodeRateLimiterTest` (10), `HandoverCodeThrottleTest` (2).
- **Still open** — the roadmap's Nov W1–W2 "Attempt caps + lockout log" bar:
  shared-store atomic counters (**Redis INCR+EXPIRE**) for multi-instance, a
  **lockout audit log**, and a **mutation-testing pass** (today's tests are JUnit
  and mutation-aware, but no mutation run has been executed).
- HMAC-SHA256 proof seal under a **derived** key (distinct from the JWT key);
  `GET /admin/deliveries/{id}/proof` re-derives and reports `verified`, and
  returns `409` when a delivery was never sealed.
- JWT + RBAC; `tokenVersion` (`tv` claim) makes **logout revoke tokens everywhere**.
- Fixed-window rate limiting with `429` + `Retry-After` (login limiter, and now
  the handover-code throttle).
- Redis fraud trails (`fraude:score:{deliveryId}`, 6-hour TTL).

---

## 9. Sprint 1 — Business Logic & AI (Session 6)

**Framing document.** `PricingService` (15 MAD base, 5/km, 45-min cap, rush 1.4×);
OR-Tools VRP auto-dispatch at payment capture only; fraud training + noisy
validation vs speed rule; HMAC proofs with fail-closed codes; fleet/proof APIs;
RAG backend (Spring AI, pgvector, cancel/details/telemetry tools).

**Advancement**
- `PricingService` — `max(15 MAD, ceil(km × 5 MAD))`, 45-minute cap, rush factor;
  the server **re-derives** the price at booking (no invented prices).
- Auto-dispatch runs **at payment capture only**; `RouteOptimizationService`
  solves the VRP with OSRM times inflated by the shared `TrafficModel`.
- Fraud model trained offline and validated on the noisy holdout vs the speed
  rule (`ml/train_fraud_model.py`, `ml/evaluate_noisy.py`).
- HMAC proofs with fail-closed handover codes (`DeliveryProofService`).
- Fleet/proof APIs (`/admin/deliveries/fraud-trails`, `/{id}/fraud-trail`,
  `/admin/deliveries/{id}/proof`).
- RAG backend: Spring AI + pgvector, `/client/chat`, with cancel / details /
  telemetry tools.

---

## 10. Sprint 2 — Integration & Frontend (Session 7)

**Framing document.** Fleet monitor (badges, score curve, threshold,
disclaimers), mid-drive chat, live maps with ARRIVED, scan modal (camera +
manual), client QR, PWA + offline, phone polish (fullscreen, 44 px GPS, 16 px
inputs), pickup-on-dropoff map.

**Advancement**
- Fleet monitor: `FraudTag` badges, `FraudTrailModal` score curve + threshold +
  on-screen disclaimers.
- Mid-drive chat widget; live client tracking strip.
- Live maps with `ARRIVED`.
- **NEW** — `ARRIVED` is now counted as **in-progress, not completed**, in both
  courier and admin stats (`CourierStatsTest.arrivedCountsAsActive`).
- Scan modal (camera `BarcodeDetector` + manual fallback), client QR.
- PWA installable + honest offline service worker (`/api` and `/osrm` never cached).
- Phone polish (fullscreen modals, 44 px targets, 16 px inputs).
- Pickup-on-dropoff map.
- **NEW** — `FraudTag` / `FraudTrailModal` follow the corrected run rule (§6).

---

## 11. Sprint 3 — Robustness & Security (Session 9)

**Framing document.** Rate limiting (login limiter plus the handover-code
throttle), logout revocation, 500 m + fail-closed codes, graceful Redis/OSRM
degradation, CHECK migration at boot. Playwright `verify_*` suites plus a
15-check end-to-end rehearsal (15/15); unit tests pin the run-rule boundaries
(inclusive threshold, runs-not-totals). CI: mvnw tests, lint/build, container
checks; Render deploys on green only.

**Advancement**
- Rate limiting: login limiter **plus the handover-code throttle** (**NEW**).
- Logout revocation via `tokenVersion`.
- 500 m GPS confirm rule + fail-closed handover codes.
- Graceful Redis / OSRM degradation (`DeliveryServiceRedisOutageTest`).
- CHECK migration at boot.
- Playwright `verify_*` suites + the 15-check end-to-end rehearsal (15/15); unit
  tests pin the run-rule boundaries.
- **NEW** — fraud verifiers hardened (`479b4b6`): stale-probe sweep + id-based
  row locators; the live verifier asserts the product contract (the run rule)
  rather than an unstable per-fix count.
- CU / CI: `mvnw` tests, lint/build, container checks; Render deploys on green only.
- Latest runs: **91 backend tests green**; `verify_stats` 17/17,
  `verify_fraud_panel` 17/17, `verify_fraud_live` 10/10, rehearsal 15/15.

---

## 12. Sprint 4 — Cloud & Industrialization (Sessions 10-11)

**Framing document.** Dockerfiles, compose, green Actions pipeline
(`checksPass`), Render Frankfurt + demo accounts, PC tunnel for full demos.
Payment simulated (no PSP). Chat unavailable on public cloud; free-tier sleep,
30-day Postgres, rate-limited OSRM/Nominatim.

**Advancement**
- Multi-stage Dockerfiles + `docker-compose.yml` (5 services).
- Green GitHub Actions pipeline gating deployment (`autoDeployTrigger: checksPass`).
- Public Render Blueprint (Frankfurt, free tier) with demo accounts.
- PC tunnel for full demos (local Ollama).
- Caveats documented rather than hidden: payment simulated; chat `unavailable`
  on the public cloud; free-tier sleep, 30-day Postgres, rate-limited
  OSRM/Nominatim.

---

## Summary — advancement since the framing document

Four commits, 23 files, +2101 / −76, all on **2026-10-13**:

| Commit | Advancement |
| :--- | :--- |
| `f29167c` | Handover-code throttle — in-memory attempt caps, ahead of the roadmap (the shared-store atomic counters, lockout log and mutation pass remain, §8); `RunRule` ships the k=3 rule into the badge (§6); `ARRIVED` counts as in-progress in stats (§10). |
| `dc1de83` | Playwright coverage for the run rule and `ARRIVED`. |
| `479b4b6` | Fraud verifiers made robust to an interrupted probe run. |
| `59a9155` | `PROJECT_OVERVIEW.md` added. |

**The headline correction:** the framing document's §6 stated the k=3 run rule was
*shipped*, but at that time the badge fired on a single flagged fix and
`RunRule.java` did not exist. That gap is now closed — the claim is true in the
code, and the §8 attempt caps are delivered ahead of schedule (the shared-store
atomic counters, lockout log and mutation pass remain on the roadmap).
