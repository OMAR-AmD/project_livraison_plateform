# SwiftDeliver — Project Report (Overview + Full Feature Set)
...
# SwiftDeliver — Project Report (Overview + Full Feature Set)

## Innovation Vision (S5): the problem the AI solves

The buyer is a Casablanca dispatch desk. Two problems cost them money every day, and neither is solved by hosting, by a Dockerfile, or by JWT:

1. **"Delivered / not delivered" disputes with nothing verifiable to check.** A client says the parcel never came; the courier says it did. The desk picks a side on one person's word.
2. **Implausible GPS trajectories go unnoticed.** A courier who claims a door from across the city, or who stalls short and then teleports to the target, is not caught by a static threshold: a speed rule catches only ~10% of teleports on the noisy holdout.

The innovation of S5 is the two AI components that resolve these, inside the running product:

- **Trajectory fraud detection** (supervised Random Forest, loaded at startup, scored in-process in Java). It turns raw GPS broadcasts into a per-fix and per-run risk decision, surfaced to the dispatcher as a review badge. It is decision-support only and never punishes automatically. Against the same noisy-holdout set, the model catches **100% of teleports vs ~10% for the speed-rule baseline**, at a **1.1% false-alarm rate** (inside the 2% budget); stall-then-vanish 59.7% vs 10%. The run-level rule (k=3 consecutive flagged fixes, or one fix ≥ 0.97) is shipped in the badge, not just measured offline.
- **RAG assistant over FAQ + the client's live orders.** It answers questions grounded in the client's own order rows (status, price, payment) and can cancel the client's own order by exact ID — not a scripted FAQ, a retrieval system over unstructured FAQ text plus structured order data.

**Explicitly baseline, not innovation** (software-engineering hygiene expected of any project): containerization, CI/CD, public hosting, JWT + RBAC, secret management, password hashing, input validation, and the refactor of the legacy code. They are necessary; they are not why a client would choose this product. The HMAC delivery proof is a security primitive that makes the fraud verdict *actionable* (a sealed, re-verifiable record), so it supports the innovation story rather than being it.

## 1. Project Overview

SwiftDeliver is a self-hosted, zero-cost last-mile delivery platform. It was designed to satisfy industrial engineering constraints (Docker, CI, public hosting) while embedding two genuine AI features inside the running product.

The platform supports three roles (Client, Courier, Admin), real-time tracking, route pricing via OSRM, secure authentication/authorization, and two AI features: a RAG-based customer assistant (local/demo-only) and a supervised ML trajectory fraud detector (cloud-deployable, in-process Java).

- Architecture: Next.js (frontend), Spring Boot (backend), PostgreSQL + pgvector, Redis, OSRM routing.
- Deployment: Docker Compose (local), Render (public cloud, zero-cost). 
- Constraints: English UI, zero-cost, one-person team, verifiable checks.

## 2. Baseline (Industrialization & Security)

- **Containerization**: Dockerfiles for frontend/backend + docker-compose.yml orchestrating Postgres, Redis, OSRM, backend, frontend.
- **CI/CD**: GitHub Actions CI (build/test style checks) with auto-deploy on Render (checksPass).
- **Public hosting**: Render free tier (frontend + backend + Key Value Redis + managed PostgreSQL considerations).
- **Authentication**: JWT access tokens, role-based access control (CLIENT/COURIER/ADMIN).
- **Security hardening**: tokenVersion + logout revocation; login rate-limiting (fixed-window per-account + per-client IP) returning 429 + Retry-After; handover-code throttling (per-delivery 5 / per-courier 20 per 15-min window, failures only, success clears the delivery counter) so the six-digit code cannot be brute-forced; password hashing (BCrypt-style standard).
- **Delivery proofs**: HMAC-sealed delivery confirmations (proof hash, deliveredAt/lat/lng, distance), verifiable by admin; prevents proof transplantation.
- **Input validation**: server-side validation for booking/quoting/tracking/cancel.
- **Resilience**: Redis read failures degrade gracefully (no 500); graceful degradation when OSRM absent (manual dispatch vs straight-line). 

## 3. Core Functionalities

### 3.1 Client
- Auth: register/login/logout, token revocation on logout.
- Create delivery: description, pickup/dropoff via map clicks (reverse geocode), or "Use my GPS location" on mobile (fills address + places pin). 
- Dropoff map reference: pickup pin shown on dropoff map (blue, non-interactive) with "Pickup" label; maps frame both when set.
- Quoting: server-side price from OSRM real road route (not straight line).
- Payment: capture payment before dispatch (unpaid orders never consume courier capacity). Payment is simulated end-to-end (no PSP, no real charge): capture flips PENDING_PAYMENT to PAID, which is what arms auto-dispatch and revenue figures.
- My deliveries: list/table + mobile cards, statuses (PENDING, ASSIGNED, IN_TRANSIT, ARRIVED, DELIVERED, CANCELLED), payment/rating states, delete delivered/cancelled.
- Handover code: on active orders a Code button shows a QR + six digits (HMAC-derived per order, owner-only, single-use); the courier scans (camera) or types them to seal delivery where GPS cannot place them within 500 m; the proof records the factor (codeVerified). Wrong guesses are throttled (429 + Retry-After).
- Live tracking: ClientLiveMap shows courier marker updates (polling), stale-position handling ("Signal lost"), scoped to that client's order.
- Rating: rate delivered orders.
- Chat widget: RAG assistant (see AI section).

### 3.2 Courier
- Auth + role.
- Assigned round: list assigned deliveries.
- Start delivery: switches to IN_TRANSIT, begins location broadcast.
- GPS modes: auto-detects environment (HTTPS + phone + geolocation → real GPS; laptop/automation → simulation). Header toggle to override (Use my real GPS / Use simulation).
- Live broadcast: periodic position updates to server (real breadcrumbs). 
- Wake lock: best-effort Screen Wake Lock while broadcasting real GPS (prevents screen-off dropping updates); released on stop/simulator switch.
- Confirm delivery: Start delivery (IN_TRANSIT) → Mark arrived at the door (ARRIVED, arms the code, never auto-seals) → seal by GPS proximity or handover code; returns HMAC-sealed proof on success; rejects if >500m away with distance reported. Skipping ASSIGNED straight to DELIVERED is refused; admin override stays unrestricted.
- Navigation context: route/ETA via OSRM where available.

### 3.3 Admin
- Auth + role (admin-only endpoints).
- Dashboard: overview/stats (deliveries, assignments, revenue-related metrics as implemented).
- Fleet monitor: live view of active trajectories; shows AI fraud badges (AI alert / AI ok), per-fix score curve, threshold, disclaimers (score not proof, synthetic training data). 
- Fraud trails: inspect historical flagged trajectories.
- User/order management: view/manage users/deliveries as applicable.
- Proof verification: view/verify HMAC seals (verifiable, persisted).

## 4. AI Features

### 4.1 RAG Customer Assistant (local/demo-only)
- Endpoint: POST /api/v1/client/chat (client-authenticated).
- Stack: Spring AI + Ollama (llama3.2:3b for chat by default, llama3.1:8b optional, nomic-embed-text for embeddings), pgvector-backed vector store (FAQ chunks).
- Capabilities: answers FAQ; grounds in client's active orders (IDs, addresses, status, price, payment); cancelling takes two steps (propose, then an explicit yes executes it) and only the client's own PENDING order, using exact ID; constrained tool use with guardrails.
- Behavior: returns friendly "unavailable" if Ollama missing/retrieval fails → rest of platform unaffected.
- UI: floating ChatWidget in client dashboard (bottom-right). Only visible to clients (mounted in ClientView).
- Cloud: not cloud-wide (no Ollama on Render) → clients on phone hitting cloud get unavailable response. Works when backend can reach local Ollama (demo/LAN).
- Demo recording (one take, scripted against the live stack): `swiftdeliver_full_demo.webm` — map booking + payment, handover code shown, courier start/arrive/scan to a codeVerified seal, 5-star rating, admin fleet view, assistant order + refund answers. Watch (unlisted): https://www.youtube.com/watch?v=QY6oAdbI_DA

### 4.2 Trajectory Fraud Detection (ML, in-product)
- Type: supervised Random Forest (60 trees). Trained offline in Python on synthetic plausible/impossible trajectories; exported as gzipped JSON.
- Runtime: loaded at backend startup, scored in-process in Java (no external paid AI calls).
- Inputs: sequence of GPS fixes for an order (timestamps/lat/lng), derived features (speed, displacement, heading changes, route plausibility signals).
- Output: per-trajectory risk score; badges in Admin → Fleet monitor: AI alert if the run-level rule fires (k=3 consecutive flagged fixes, or a single fix ≥ 0.97), else AI ok. 
- Explainability: score curve (per-fix), decision threshold shown, UI states "score is not proof of wrongdoing" and "training data is synthetic".
- Integration: decision-support only (no automatic punitive action). Surfaces in running product for human review.
- Noisy-holdout validation (ml/evaluate_noisy.py, ml/noisy_eval_report.json): the SHIPPED forest + threshold scored on real OSRM road geometry with 10 m GPS noise plus an unseen circling pattern — broadcast precision 0.636 / recall 0.443 at 1.10% false alarms (inside the 2% budget); teleport caught 100%, sprint 79.5%, stall-then-vanish 59.7% vs 10% for a speed rule. Known limit found honestly: the unseen circling pattern is nearly blind (0.3%) — the model needs retraining on field data, not just more simulation.
- Run-level rule (ml/tune_run_rule.py, ml/measure_or_clause.py, ml/audit_k3.py): k=3 consecutive flagged fixes, OR any single fix ≥ 0.97 (threshold tuned on a disjoint seed set) — 29/400 honest runs flagged (7.25%, 95% CI ≈ 5–10%), fraud-run recall 71/98 (72%): teleport 23/25, sprint 23/25, drift-then-vanish 24/25, loop 1/23 (about 25 runs each). `drift-then-vanish` is the `stall-then-vanish` above and `loop` the `circling` pattern (0.3% per-fix, 1/23 per-run — same generator, different units). Toggle rule, pre-committed not statistical: past 3 clause-attributable honest flags per 150 field runs raise the threshold, past 5 drop the clause (evaluated at freeze or 150 runs, whichever first); raising it will cost teleport recall, re-measured not assumed. **Shipped**: the rule now lives in `RunRule` and drives both the live alert (`TrajectoryFraudService`) and the dashboard badge (`FraudTrailStore.flagged()`), so the two cannot drift apart.

## 5. Technical Details

- **Frontend**: Next.js 14, React 18, TailwindCSS, Leaflet + react-leaflet (maps), dynamic map import (SSR off), wake lock API usage where supported.
- **Backend**: Spring Boot (Java), Spring Security + JWT, Spring AI, JPA/Hibernate, WebSocket/STOMP for live updates where used, scheduled tasks.
- **Data**: PostgreSQL (relational), pgvector (FAQ embeddings), Redis (live positions, caching/session-related as configured).
- **Routing**: OSRM (/route/v1/driving) proxied via Next rewrites; graceful if unreachable.
- **GPS**: client geolocation API, reverse geocoding via Nominatim (with address truncation + coordinate fallback), stable coordKey to avoid double-geocoding.
- **Testing/Verification**: Playwright-based verification scripts (verify_*.js/.mjs) covering auth hardening, proofs, GPS modes/pickup, real GPS + wake lock, client map, basemap zoom, fraud panel, handover codes, PWA/offline, full rehearsal (15/15). Mutation-aware checks included. The fraud suites (`verify_fraud_panel.js`, `verify_fraud_live.js`) now also assert the badge's run rule (k=3 consecutive flagged fixes, or a single fix ≥ 0.97) at the fleet summary endpoint the dashboard reads, and `verify_stats.js` asserts an ARRIVED order counts as in-progress and not as completed. `verify_assign_ui` fails its last 2 checks only when live user orders shift dispatch balance onto courier2, making it the "(current)" — correctly non-reselectable per the no-reassign rule; all 8 stacking/behavior checks pass.
- **CI inventory**: GitHub Actions runs `mvnw package` (backend unit tests: rate limiter, fraud-service parity, fraud store, proof sealing, health) plus the Playwright suites; Render auto-deploys only on green (`checksPass`).
- **Zero-cost**: no paid APIs/cloud services; all AI local or in-process.

### Mobile Web (PWA + phone polish)
- Installable: manifest + locally generated icons, standalone display.
- Offline honesty: hand-rolled service worker (navigations network-first with /offline fallback; static cache-first; /api and /osrm never cached), static /offline page.
- Phone polish: fullscreen modals, 44px GPS target, 16px inputs (no iOS auto-zoom), dvh chat panel with send hint.

## 6. Limitations & Scope

- **Chat availability**: requires Ollama running locally (llama3.2:3b + nomic-embed-text). Unavailable on Render by design (zero-cost). 
- **Nominatim**: external reverse/geocode lookups used in browser; tests stub it for determinism.
- **OSRM**: demo/public or local container; absence degrades to manual dispatch.
- **Wake lock**: best-effort; on some browsers/OS the "Signal lost" badge can still appear if screen locks aggressively (documented).
- **Fraud model**: trained on synthetic data; explicitly presented as decision-support with disclaimers (not evidentiary proof).
- **Scaling risks (demo-grade, by design)**: Render free sleeps (~15 min idle, ~60 s wake) and deletes Postgres 30 days after creation; routing and geocoding ride rate-limited public servers (OSRM demo, Nominatim). Fine for a jury demo, not a production plan — each has a named paid/self-hosted replacement.

### Field-retraining plan (the circling blind spot)

The noisy holdout surfaced one real weakness, reported honestly rather than hidden: the unseen **circling/loop** pattern is nearly blind — 0.28% per-fix recall (1 of 354 fixes) and 1 of 23 runs — while teleport is caught 100%, sprint 79.5% and stall-then-vanish 59.7% at the shipped threshold (0.82, 60 trees). The cause is the training data, not the model class: every trajectory is synthetic, and a loop's signature (long path, little net progress) is averaged away by per-step features that were never tuned against a real loop. The pre-committed run-rule toggle (`ml/audit_k3.py`) already bounds the collateral — 7.25% honest-run false alarms — but it cannot recover a pattern the forest cannot see. Closing the gap is a data problem, and this is the plan:

1. **Collect real fixed features, not raw traces.** The platform already records every verdict to Redis under `fraude:score:{deliveryId}` (120-point cap, 6 h TTL) — proof the feature path runs in production, but far too short-lived to train on. Add an opt-in admin export that persists the anonymised feature row per fix (speed, displacement, heading change, stop/burst counts) to Postgres, with the raw coordinate truncated to ~50 m so no customer address is stored. Cadence: the first 200 completed runs or 4 weeks, whichever comes first.
2. **Label from the workflow that already exists.** Clients, couriers and admins already produce weak labels: "delivered but not received" disputes, client complaints, and the admin's accept/dismiss on the alert badge. Target ≥ 30 confirmed loop-like incidents before trusting a recall figure; below that, report the gap, not a percentage.
3. **Add the features a loop needs.** Cumulative path efficiency over a sliding window (driven distance ÷ straight-line displacement), net-displacement ratio, and revisit/cluster counts — exactly the signals the current per-step features average away, and cheap to compute in `TrajectoryFraudService` beside the existing ones.
4. **Retrain behind the same gate, then compare.** Keep the 2% false-alarm budget and the Java↔Python parity test (< 1e-9). Ship a new forest only if loop recall improves without pushing broadcast false alarms past 2%; re-run `ml/evaluate_noisy.py` as the regression gate and record the per-pattern table, as now.
5. **Keep rollback to one config flip.** The model artifact is a versioned JSON loaded at startup behind `application.ai.*`; keep the previous version on disk so a bad retrain is a one-line revert plus restart, with no redeploy.

Until step 1 yields data, the loop blind spot is stated here and on the alert itself, and the badge stays decision-support: it surfaces a candidate for review, it never accuses a courier on its own.

## 7. Access & Demo Notes

- Live cloud: Frontend https://swiftdeliver-frontend.onrender.com, Backend https://swiftdeliver-backend-j47m.onrender.com (use correct backend suffix as deployed).
- Local: http://localhost:3000/login (frontend), http://localhost:8080 (backend), via docker compose up --build.
- Demo accounts (password password123): admin@swift.com, courier1@swift.com, courier2@swift.com, client1@swift.com, client2@swift.com, client3@swift.com.
