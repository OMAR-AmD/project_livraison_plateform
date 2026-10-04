# SwiftDeliver — Project Report (Overview + Full Feature Set)
...
# SwiftDeliver — Project Report (Overview + Full Feature Set)

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
- **Security hardening**: tokenVersion + logout revocation; login rate-limiting (fixed-window per-account + per-client IP) returning 429 + Retry-After; password hashing (BCrypt-style standard).
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
- Handover code: on active orders a Code button shows a QR + six digits (HMAC-derived per order, owner-only, single-use); the courier scans (camera) or types them to seal delivery where GPS cannot place them within 500 m; the proof records the factor (codeVerified).
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
- Stack: Spring AI + Ollama (llama3.1:8b for chat, nomic-embed-text for embeddings), pgvector-backed vector store (FAQ chunks).
- Capabilities: answers FAQ; grounds in client's active orders (IDs, addresses, status, price, payment); can call cancelDeliveryFunction to cancel only the client's own order, using exact ID; constrained tool use with guardrails.
- Behavior: returns friendly "unavailable" if Ollama missing/retrieval fails → rest of platform unaffected.
- UI: floating ChatWidget in client dashboard (bottom-right). Only visible to clients (mounted in ClientView).
- Cloud: not cloud-wide (no Ollama on Render) → clients on phone hitting cloud get unavailable response. Works when backend can reach local Ollama (demo/LAN).
- Demo recording (one take, scripted against the live stack): `swiftdeliver_full_demo.webm` — map booking + payment, handover code shown, courier start/arrive/scan to a codeVerified seal, 5-star rating, admin fleet view, assistant order + refund answers. [LINK TO UPLOAD — replace before submitting]

### 4.2 Trajectory Fraud Detection (ML, in-product)
- Type: supervised Random Forest (60 trees). Trained offline in Python on synthetic plausible/impossible trajectories; exported as gzipped JSON.
- Runtime: loaded at backend startup, scored in-process in Java (no external paid AI calls).
- Inputs: sequence of GPS fixes for an order (timestamps/lat/lng), derived features (speed, displacement, heading changes, route plausibility signals).
- Output: per-trajectory risk score; badges in Admin → Fleet monitor: AI alert if above threshold, else AI ok. 
- Explainability: score curve (per-fix), decision threshold shown, UI states "score is not proof of wrongdoing" and "training data is synthetic".
- Integration: decision-support only (no automatic punitive action). Surfaces in running product for human review.
- Noisy-holdout validation (ml/evaluate_noisy.py, ml/noisy_eval_report.json): the SHIPPED forest + threshold scored on real OSRM road geometry with 10 m GPS noise plus an unseen circling pattern — broadcast precision 0.636 / recall 0.443 at 1.10% false alarms (inside the 2% budget); teleport caught 100%, sprint 79.5%, stall-then-vanish 59.7% vs 10% for a speed rule. Known limit found honestly: the unseen circling pattern is nearly blind (0.3%) — the model needs retraining on field data, not just more simulation.

## 5. Technical Details

- **Frontend**: Next.js 14, React 18, TailwindCSS, Leaflet + react-leaflet (maps), dynamic map import (SSR off), wake lock API usage where supported.
- **Backend**: Spring Boot (Java), Spring Security + JWT, Spring AI, JPA/Hibernate, WebSocket/STOMP for live updates where used, scheduled tasks.
- **Data**: PostgreSQL (relational), pgvector (FAQ embeddings), Redis (live positions, caching/session-related as configured).
- **Routing**: OSRM (/route/v1/driving) proxied via Next rewrites; graceful if unreachable.
- **GPS**: client geolocation API, reverse geocoding via Nominatim (with address truncation + coordinate fallback), stable coordKey to avoid double-geocoding.
- **Testing/Verification**: Playwright-based verification scripts (verify_*.js/.mjs) covering auth hardening, proofs, GPS modes/pickup, real GPS + wake lock, client map, basemap zoom, fraud panel, handover codes, PWA/offline, full rehearsal (15/15). Mutation-aware checks included. `verify_assign_ui` fails its last 2 checks only when live user orders shift dispatch balance onto courier2, making it the "(current)" — correctly non-reselectable per the no-reassign rule; all 8 stacking/behavior checks pass.
- **CI inventory**: GitHub Actions runs `mvnw package` (backend unit tests: rate limiter, fraud-service parity, fraud store, proof sealing, health) plus the Playwright suites; Render auto-deploys only on green (`checksPass`).
- **Zero-cost**: no paid APIs/cloud services; all AI local or in-process.

### Mobile Web (PWA + phone polish)
- Installable: manifest + locally generated icons, standalone display.
- Offline honesty: hand-rolled service worker (navigations network-first with /offline fallback; static cache-first; /api and /osrm never cached), static /offline page.
- Phone polish: fullscreen modals, 44px GPS target, 16px inputs (no iOS auto-zoom), dvh chat panel with send hint.

## 6. Limitations & Scope

- **Chat availability**: requires Ollama running locally (llama3.1:8b + nomic-embed-text). Unavailable on Render by design (zero-cost). 
- **Nominatim**: external reverse/geocode lookups used in browser; tests stub it for determinism.
- **OSRM**: demo/public or local container; absence degrades to manual dispatch.
- **Wake lock**: best-effort; on some browsers/OS the "Signal lost" badge can still appear if screen locks aggressively (documented).
- **Fraud model**: trained on synthetic data; explicitly presented as decision-support with disclaimers (not evidentiary proof).
- **Scaling risks (demo-grade, by design)**: Render free sleeps (~15 min idle, ~60 s wake) and deletes Postgres 30 days after creation; routing and geocoding ride rate-limited public servers (OSRM demo, Nominatim). Fine for a jury demo, not a production plan — each has a named paid/self-hosted replacement.

## 7. Access & Demo Notes

- Live cloud: Frontend https://swiftdeliver-frontend.onrender.com, Backend https://swiftdeliver-backend-j47m.onrender.com (use correct backend suffix as deployed).
- Local: http://localhost:3000/login (frontend), http://localhost:8080 (backend), via docker compose up --build.
- Demo accounts (password password123): admin@swift.com, courier1@swift.com, courier2@swift.com, client1@swift.com, client2@swift.com, client3@swift.com.
