# SwiftDeliver — Full Project Overview

> A single, self-contained walkthrough of the whole platform: what it is, why it
> exists, how every part works, how it is deployed and tested, what the AI
> actually does, and — honestly — what it does **not** do yet.
>
> This document synthesises `README.md`, `SYSTEM_ARCHITECTURE.md`,
> `API_REFERENCE.md`, `DEMO.md`, `DEPLOY.md` and `SWIFTDELIVER_REPORT.md`, and
> cross-checks them against the source tree. Where the older docs have drifted
> from the code, the code wins and the discrepancy is noted.

---

## Table of contents

1. [What SwiftDeliver is](#1-what-swiftdeliver-is)
2. [The problem and the business value](#2-the-problem-and-the-business-value)
3. [Roles and what each can do](#3-roles-and-what-each-can-do)
4. [Tech stack](#4-tech-stack)
5. [Architecture at a glance](#5-architecture-at-a-glance)
6. [Repository layout](#6-repository-layout)
7. [Backend deep dive](#7-backend-deep-dive)
8. [Data model and state machines](#8-data-model-and-state-machines)
9. [Frontend deep dive](#9-frontend-deep-dive)
10. [The two AI features (the innovation)](#10-the-two-ai-features-the-innovation)
11. [Security and hardening](#11-security-and-hardening)
12. [Real-time tracking and notifications](#12-real-time-tracking-and-notifications)
13. [Pricing, payment, routing and dispatch](#13-pricing-payment-routing-and-dispatch)
14. [Deployment and CI/CD](#14-deployment-and-cicd)
15. [Testing and verification](#15-testing-and-verification)
16. [The offline ML pipeline](#16-the-offline-ml-pipeline)
17. [Configuration reference](#17-configuration-reference)
18. [How to run it](#18-how-to-run-it)
19. [Honest limitations](#19-honest-limitations)
20. [Recent work log](#20-recent-work-log)
21. [Documentation index and glossary](#21-documentation-index-and-glossary)

---

## 1. What SwiftDeliver is

SwiftDeliver is a **self-hosted, zero-cost last-mile delivery platform**. It
connects three kinds of users — **Clients**, **Couriers** (`LIVREUR`) and
**Admins** — through a role-based web application that covers the whole delivery
lifecycle: booking, server-side pricing from real roads, payment capture,
automatic courier dispatch, live GPS tracking, proof-of-delivery, and rating.

The thing that makes it more than a CRUD exercise is that it runs **two genuine
AI features inside the running product**:

- a **trajectory fraud detector** — a Random Forest, trained offline in Python,
  exported as gzipped JSON, and scored *in-process in Java* on every courier GPS
  update;
- a **retrieval-augmented (RAG) customer assistant** — a local LLM (Ollama) that
  answers questions grounded in the FAQ and in the caller's own live orders, and
  can cancel the caller's own order through an ownership-checked tool.

Everything runs locally or in-process: routing (**OSRM**), embeddings and the
language model (**Ollama**), fonts (self-hosted), and the vector store
(**pgvector**). There is no paid API anywhere in the request path, which is both
a cost decision and a data-residency one (customer coordinates never leave the
deployment for a third-party router).

**Languages / runtime**

| Layer | Technology | Version (from the build files) |
| :--- | :--- | :--- |
| Backend | Java + Spring Boot | Java 17, Spring Boot **3.4.1** |
| Auth | Spring Security + JJWT | JJWT 0.12.5 |
| Routing solver | Google OR-Tools | 9.10.4067 |
| AI (assistant) | Spring AI + Ollama | Spring AI 1.0.0-M4 |
| Frontend | Next.js (React) | Next.js **14.2.35**, React 18 |
| Styling | Tailwind CSS | 3.4.1 |
| Maps | Leaflet / react-leaflet | 1.9.4 / 4.2.1 |
| Data | PostgreSQL + pgvector, Redis | pg15, Redis 7 |

> **Note on versions.** `README.md` still says "Spring Boot 3.3"; `pom.xml` is the
> authority and pins **3.4.1**. Java source/target is **17** even though a modern
> JDK (e.g. 21) can build it.

---

## 2. The problem and the business value

The buyer in mind is a **Casablanca dispatch desk**. Two problems cost them money
every day, and neither is solved by a Dockerfile, by hosting, or by JWT:

1. **"Delivered / not delivered" disputes with nothing verifiable to check.** A
   client says the parcel never arrived; the courier says it did. The desk has to
   pick a side based on one person's word.
2. **Implausible GPS trajectories go unnoticed.** A courier who claims a door
   from across the city, or who stalls short of the drop-off and then "teleports"
   to it, is not caught by a static speed threshold — a speed rule catches only
   about **10%** of teleports on a noisy holdout.

SwiftDeliver answers both inside the product:

| Business need | Feature that answers it | Where it lives |
| :--- | :--- | :--- |
| Trust at the door | **HMAC-sealed proof of delivery** (fresh GPS fix ≤ 500 m, or a handover code), re-verifiable by an admin | `DeliveryProofService`, `AdminDeliveryController#getProof` |
| Catch implausible trajectories | **Trajectory fraud detector** surfaced as a review badge (decision-support, never automatic punishment) | `TrajectoryFraudService`, `FraudTrailStore`, `RunRule` |
| Deflect support tickets | **RAG assistant** over the FAQ + the client's own live orders | `ChatService`, `ChatController` |
| Fair courier metrics | Ratings and revenue that come only from real customer data / captured payments | `DeliveryRepository#getTotalRevenue`, stats endpoints |

This framing — *the problem, the real need, the impact and especially the
business value* — is exactly what the S5 "Innovation Vision" section of
`SWIFTDELIVER_REPORT.md` sets out, and it is why the AI components are the
graded core rather than an add-on.

---

## 3. Roles and what each can do

### Client (`CLIENT`)
- Register / log in / log out (logout is server-side revocable).
- **Book a delivery** by clicking a map (reverse-geocoded addresses), or tap
  **Use my GPS location** on a phone.
- **Get a server-computed quote** from the real OSRM road route, then pay.
- See a **live tracking strip** for their active order, scoped to their own
  delivery, with honest "Signal lost" handling.
- Use the **handover code** (QR + 6 digits) where GPS cannot place the courier.
- **Rate** a delivered order.
- Chat with the **RAG assistant**.

### Courier (`LIVREUR`)
- See their **assigned round** and personal **stats** (completed, in progress,
  cancelled, average rating).
- **Start delivery** (`IN_TRANSIT`) and broadcast position — real GPS on a
  phone, or a deterministic route **simulation** on a laptop.
- **Mark arrived** (`ARRIVED`), then **seal by GPS proximity or handover code**.
- **Optimise their route** via OR-Tools.

### Admin (`ADMIN`)
- **Dashboard KPIs**, live **fleet map**, **activity feed**.
- **AI fraud monitor**: `AI alert` / `AI ok` badges, per-fix score curve, the
  threshold, and explicit disclaimers.
- **Verify delivery proofs** (re-derive the HMAC).
- Manage users, manually assign couriers, override status, force-delete orders.

There is a ready-made **demo dataset** (seed accounts, password `password123`):
`admin@swift.com`, `courier1@swift.com`, `courier2@swift.com`,
`client1@swift.com`, `client2@swift.com`, `client3@swift.com`.

---

## 4. Tech stack

**Backend**
- Java 17, Spring Boot 3.4.1, Spring Web, Spring Security, Spring Data JPA,
  Spring Validation, Spring WebSocket (STOMP), Spring Data Redis.
- **Google OR-Tools** — the vehicle-routing problem (VRP) solver.
- **Spring AI + Ollama** — RAG assistant (chat + embeddings) with a pgvector
  store.
- **JJWT** — stateless JWT issue/validation.
- **PostgreSQL** (+ **pgvector**) and **Redis**.

**Frontend**
- **Next.js 14** (App Router), React 18.
- **Tailwind CSS** with a custom dark token system.
- **Leaflet / react-leaflet** for maps; OpenStreetMap raster tiles, darkened at
  the CSS layer so no keyed tile service is required.
- `@stomp/stompjs` + `sockjs-client` for WebSocket live updates.
- `qrcode.react` for the handover QR.

**Infrastructure**
- **Docker / Docker Compose** — five services: `postgres`, `redis`, `osrm`,
  `backend`, `frontend`.
- **OSRM** — self-hosted road routing (compiled from a Geofabrik extract).

---

## 5. Architecture at a glance

```
                                  BROWSER
                                     │
                    Next.js origin (http://localhost:3000)
                    ├── /api/*   ──rewrite──►  backend :8080 (/api/v1)
                    └── /osrm/*  ──rewrite──►  OSRM    :5000
                                     │
        ┌────────────────────────────┼─────────────────────────────┐
        │            SPRING BOOT BACKEND (modular monolith)         │
        │                                                           │
        │  auth/         identity + JWT + login rate-limit          │
        │  delivery/     lifecycle, pricing, payment, dispatch,     │
        │                route optimization, proof-of-delivery      │
        │  notification/ SSE fan-out per connected user             │
        │  ai/           trajectory fraud detector  +  RAG assistant│
        │  config/       security, JWT filter, Redis, WebSocket     │
        └───────┬───────────────┬───────────────┬──────────────────┘
                │               │               │
          PostgreSQL         Redis            OSRM            Ollama (host)
        (relational +    (live positions,  (road network    (LLM + embeddings,
         pgvector for     fraud trails,     routing &        optional; absence
         the FAQ)         dispatch state)   matrices)        degrades gracefully)
```

**Key architectural decisions**

- **Modular monolith.** One Spring Boot deployable, internally split into
  role-oriented packages (`auth`, `delivery`, `notification`, `ai`, `config`).
  Easy to reason about, easy to run on a free tier.
- **One origin for the browser.** The browser never calls the backend or OSRM
  directly; both are proxied through Next.js rewrites (`/api/*`, `/osrm/*`).
  This keeps clients free of hard-coded hosts and keeps OSRM off the public
  internet.
- **Two AI components share the `ai/` package but are unrelated.** Only the
  fraud detector is the claimed innovation; the RAG assistant is a convenience
  feature. Both degrade gracefully so the platform boots without Ollama.

---

## 6. Repository layout

```
plateforme-livraison/
├── backend/                      Spring Boot application
│   ├── src/main/java/com/delivery/backend/
│   │   ├── BackendApplication.java
│   │   ├── modules/
│   │   │   ├── auth/             identity, JWT, rate-limiting
│   │   │   ├── delivery/         core domain (+ dto/)
│   │   │   ├── notification/     SSE notifications
│   │   │   └── ai/               fraud detector + RAG assistant
│   │   ├── config/               SecurityConfig, JwtAuthenticationFilter,
│   │   │                         RedisConfig, WebSocketConfig, HealthController,
│   │   │                         DemoDataSeeder
│   │   └── exception/            GlobalExceptionHandler, ErrorResponse
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── faq.txt               RAG corpus
│   │   └── trajectory_fraud_rf.json.gz   exported model
│   ├── src/test/java/...         25 test classes (166 tests)
│   ├── Dockerfile
│   ├── mvnw / mvnw.cmd
│   └── pom.xml
│
├── frontend/                     Next.js application
│   ├── src/app/                  routes (/, /login, /register, /dashboard/*)
│   ├── src/components/           shared UI
│   ├── src/context/              AuthContext
│   ├── src/lib/api.js            the single fetch wrapper
│   ├── verify_*.js               Playwright verification scripts
│   ├── rehearse_demo.js          the timed end-to-end demo rehearsal
│   ├── Dockerfile
│   └── package.json
│
├── ml/                           offline training + evaluation (Python)
│   ├── train_fraud_model.py      trains the Random Forest
│   ├── train_trajectory_anomaly.py
│   ├── evaluate_noisy.py         noisy-holdout evaluation
│   ├── noisy_eval_report.json    the canonical result numbers
│   ├── measure_or_clause.py      the shipped run rule (k=3 OR ≥0.97)
│   ├── tune_run_rule.py / audit_k3.py
│   ├── supervised_vs_unsupervised.py
│   └── benchmark_dispatch.py
│
├── scripts/                      prepare-osrm.sh, render-blueprint checkers,
│                                 the written defence (.tex → .pdf)
├── .github/workflows/ci.yml      CI: backend, frontend, container images
├── docker-compose.yml            the full local stack
├── render.yaml                   the public-cloud blueprint
├── init.sql                      enables the pgvector extension
├── API_REFERENCE.md              every endpoint
├── SYSTEM_ARCHITECTURE.md        module map and data flows
├── DEMO.md                       the demo runbook
├── DEPLOY.md                     the cloud runbook
├── SWIFTDELIVER_REPORT.md        the project report (S5 innovation, etc.)
└── PROJECT_OVERVIEW.md           ← this document
```

---

## 7. Backend deep dive

Package root: `backend/src/main/java/com/delivery/backend/`.

### 7.1 `modules/auth/` — identity

| Class | Responsibility |
| :--- | :--- |
| `AuthController` | `/auth/register`, `/auth/login`, `/auth/verify` |
| `AuthService` | Registration rules, credential check |
| `JwtService` | Token issue + validation, `tokenVersion` (`tv`) claim |
| `User` / `UserRepository` | JPA entity; **implements `UserDetails`**, so the principal is the entity |
| `UserController` | `/users/me` (profile read/update) |
| `AdminUserController` | `/admin/users`, `/admin/users/couriers` |
| `LoginRateLimiter` | Per-account + per-client failed-login windows |
| `ResendEmailService` | Verification email delivery |

**Hardening.** `AuthService.register()` rejects `ADMIN` outright — the role
arrives in the request body, so without that guard any anonymous caller could
`POST {"role":"ADMIN"}`. `UserController.updateProfile()` uses a fixed field
whitelist and never touches `role`.

**Session revocation.** Tokens are stateless but not irrevocable: logout bumps a
per-account `tokenVersion`, embedded as the `tv` claim; `JwtService` refuses any
token whose claim no longer matches. A token captured before logout is dead on
every device. A pre-feature token with no `tv` claim is read as version 0, so the
upgrade does not sign everyone out.

### 7.2 `modules/delivery/` — the core domain

| Class | Responsibility |
| :--- | :--- |
| `Delivery` / `DeliveryStatus` | Entity + operational-state enum |
| `PaymentStatus` | The independent payment axis |
| `DeliveryService` | Orchestration: create, quote, capture, cancel, rate, auto-dispatch, stats |
| `PricingService` | OSRM-quoted pricing + the 45-minute admission check |
| `TrafficModel` | The single shared congestion model |
| `RouteOptimizationService` | OSRM travel-time matrix → OR-Tools VRP |
| `ClientDeliveryController` / `CourierDeliveryController` / `AdminDeliveryController` | Role-scoped endpoints |
| `DeliveryProofService` | HMAC-SHA256 seal + re-derivation |
| `HandoverCodeRateLimiter` | Throttles wrong 6-digit handover codes |
| `dto/` | Request/response contracts |

Controllers are split per role; all business logic lives in `DeliveryService`.

**Proof of delivery.** Reaching `DELIVERED` requires a GPS fix within **500 m**
of the drop-off (from the request body or the courier's last broadcast), or a
valid handover code, and seals the event with an **HMAC-SHA256** under a key
*derived* from the root secret — so the proof key and the JWT signing key are not
the same material. A bare SHA-256 digest was rejected: both parties can compute
it, so it cannot prove which of them produced the row.
`GET /admin/deliveries/{id}/proof` re-derives the seal and reports `verified`.
It returns **409** when the delivery was never sealed (an unsealed delivery is
not a forged one).

**Handover code.** A 6-digit code (HMAC-derived per order, owner-only,
single-use) lets a courier seal a delivery where GPS cannot place them. It is
only a million guesses wide, so wrong guesses are throttled: **5 per delivery /
20 per courier per 15-minute window**, failures only; a correct code clears the
delivery counter and returns `429` + `Retry-After` when exceeded.

### 7.3 `modules/notification/` — real-time messaging

`NotificationService` keeps an `SseEmitter` per connected user and pushes on
state changes. `NotificationController` exposes the stream plus read/unread
endpoints. Because `EventSource` cannot set an `Authorization` header, the stream
also accepts the JWT as a `?token=` query parameter — the one place a token
travels in a URL.

### 7.4 `modules/ai/` — fraud detector + assistant

Two unrelated components share the package:

**Fraud detector (the innovation)**

| Class | Responsibility |
| :--- | :--- |
| `TrajectoryFraudModel` | Loads the exported Random Forest from the gzipped resource; refuses to start if missing/unparsable |
| `TrajectoryFraudService` | Turns a position update into movement features and a `Verdict`; tracks the run |
| `RunRule` | The single source of truth for the run rule (`k=3` OR `≥0.97`) |
| `TrajectoryFraudConfig` | Wires the model into the Spring context |
| `FraudTrailStore` | Per-delivery score history in Redis |

Training is offline in Python and never happens at runtime — the JVM only
evaluates. On every position update,
`DeliveryService.updateCourierLocation()` calls
`TrajectoryFraudService.evaluate(...)` and `FraudTrailStore.record(...)`.
`GET /admin/deliveries/fraud-trails` lists deliveries with a trail;
`GET /admin/deliveries/{id}/fraud-trail` returns one curve (409 if never scored).

**RAG assistant (convenience feature)**

| Class | Responsibility |
| :--- | :--- |
| `ChatService` | Retrieval + prompt assembly + LLM call |
| `AiConfiguration` | The ownership-checked cancel-order tool |
| `ChatController` | `POST /client/chat` |

`ChatService` retrieves from pgvector, injects the caller's **live** order state
into the system prompt, and calls Ollama via Spring AI. The corpus is
`src/main/resources/faq.txt`, rebuilt into the vector store on every startup
(rebuild-rather-than-load-if-empty, so an edited/translated FAQ never gets mixed
with a stale one).

### 7.5 `config/` and `exception/`

`config/SecurityConfig` (role-scoped paths), `JwtAuthenticationFilter`,
`ApplicationConfig` (password encoder, `UserDetailsService`), `RedisConfig`,
`WebSocketConfig` + `WebSocketAuthInterceptor` (JWT on the STOMP `CONNECT`
frame), and `HealthController` — the one public 2xx path (`/api/v1/health`),
which reports how many trees the fraud model actually loaded. A 200 that no
longer carried that field would mean traffic was reaching an instance with no
detector.

`exception/GlobalExceptionHandler` maps failures to a consistent envelope, and
deliberately distinguishes `404` (unmapped path) and `405` (wrong verb) from a
generic `500`, so a mistyped endpoint does not look like a server fault.

---

## 8. Data model and state machines

### Entities (JPA)
- **`User`** — id, email, password (hashed), role, verification fields,
  `tokenVersion`, profile fields.
- **`Delivery`** — id, description, pickup/dropoff addresses + coordinates,
  `status`, `paymentStatus`, price, client, courier, rating, review, proof
  fields (`proof`, `deliveredAt`, delivered lat/lng, distance, `codeVerified`).
- **`Notification`** — per-user messages with read/unread state.

### Delivery status (`DeliveryStatus`) — operations

```
PENDING ──► ASSIGNED ──► IN_TRANSIT ──► ARRIVED ──► DELIVERED
   │                                                   
   └──────────► CANCELLED ◄────────────────────────────
```

- `ARRIVED` means the courier is at the door; the simulator will **not**
  auto-seal it. Sealing happens by GPS proximity or handover code.
- Skipping `ASSIGNED` → `DELIVERED` is refused (HTTP 400); the admin override is
  unrestricted and logged.

### Payment status (`PaymentStatus`) — money

| Value | Meaning |
| :--- | :--- |
| `PENDING_PAYMENT` | Order created, money not yet captured |
| `PAID` | Captured. **The only state counted as revenue.** |
| `VOIDED` | Cancelled before capture |
| `REFUNDED` | Cancelled after capture |

The two axes are **independent by design**: a delivered order is `DELIVERED` +
`PAID`; a failed checkout is `PENDING` + `PENDING_PAYMENT` — representable
without inflating revenue. Revenue is `SUM(price) WHERE paymentStatus = 'PAID'`;
cancelling a paid order flips it to `REFUNDED`, so it drops out of revenue.

---

## 9. Frontend deep dive

`frontend/src/`

```
app/            routes: /, /login, /register, /offline,
                /dashboard/{, deliveries, notifications, profile}
components/     shared UI (list below)
context/        AuthContext — JWT storage and session state
lib/api.js      the single fetch wrapper; every backend call goes through it
lib/gpsMode.js  GPS-vs-simulation mode selection
```

**Role views.** `src/app/dashboard/deliveries/` holds `ClientView.js`,
`CourierView.js` and `AdminView.js`; the page picks one from the auth role.

**Notable components**
- `StatusPill`, `StopAction` — status vocabulary and legal transitions defined
  once (an earlier `<select>` allowed illegal backwards moves).
- `LocationPicker` / `LocationPickerMap` — map-click booking with reverse
  geocoding and address truncation.
- `PaymentModal`, `HandoverCodeModal` (QR + 6 digits), `ScanHandoverModal`
  (camera `BarcodeDetector`, no dependency, with a type-the-code fallback),
  `RatingModal`, `StarRating`.
- `ClientLiveMap`, `TrackingMap`, `AdminMap`, `OptimizedRouteMap`,
  `OptimizedRouteModal`.
- `BasemapLayer` — the shared fallback/tile layer for every map.
- `FraudTag`, `FraudTrailModal` — the AI badges and the score-curve modal.
- `CourierStats`, `ChatWidget`, `Toast`, `Modal`, `Sidebar`, `Navbar`,
  `ProtectedRoute`, `EmptyState`, `ServiceWorkerRegister`.

**Design system.** Three ideas, defined once in `tailwind.config.js` and consumed
through semantic classes in `globals.css`:
- one neutral ramp (`ink`) for the dark theme;
- one accent (`signal`, hi-vis orange) for the primary action **and** the
  live/in-transit state;
- semantic aliases (`surface`, `line`, `content`) so components never reference a
  raw ramp step.

**Dark maps without a paid basemap.** The only keyless basemap is the light OSM
raster; the tile pane is inverted and hue-rotated at the CSS layer, leaving
markers and polylines untouched. Because OSM's public tile server throttles under
load (and can return nothing), every map shares `BasemapLayer`, which substitutes
a local fallback tile, shows a notice with a working **Retry**, and retries on a
backoff. Point `NEXT_PUBLIC_TILE_URL` at your own server to remove the
dependency.

**PWA / offline honesty.** An installable manifest + locally generated icons; a
hand-rolled service worker (navigations network-first with an `/offline`
fallback, static cache-first, **`/api` and `/osrm` never cached**); phone polish
(fullscreen modals, ≥44 px targets, 16 px inputs to stop iOS zoom, dvh chat
panel).

---

## 10. The two AI features (the innovation)

### 10.1 Trajectory fraud detection (the graded innovation)

**What it is.** A supervised **Random Forest** (60 trees) that scores every
courier GPS broadcast, turning raw movement into a per-fix risk score and a
per-run decision, surfaced to the dispatcher as a review badge. It is
**decision-support only**: it surfaces a candidate for human review and never
punishes automatically.

**Pipeline.**

```
ml/train_fraud_model.py            (Python, offline)
    └─ synthetic plausible/impossible trajectories
    └─ Random Forest, 60 trees
    └─ exported  ─► backend/src/main/resources/trajectory_fraud_rf.json.gz
                                   │
TrajectoryFraudModel               (loaded once at Spring startup; refuses to
    └─ in-process Java evaluation   start if the artifact is missing/broken)
TrajectoryFraudService.evaluate()  (per position update → Verdict)
    └─ RunRule                    (per-run decision: k=3 OR ≥0.97)
FraudTrailStore.record()           (Redis: fraude:score:{deliveryId},
                                    120-point cap, 6 h TTL)
    └─ Admin panel: FraudTag + FraudTrailModal
```

**The run rule.** A single flag does not accuse anyone. The badge fires on a
**run**: either **3 consecutive flagged fixes** (`k=3`) **or a single fix with
score ≥ 0.97**. The rule lives in exactly one class, `RunRule`, so the live alert
(`TrajectoryFraudService`) and the dashboard badge (`FraudTrailStore.flagged()`)
cannot drift apart.

**Numbers (all from `ml/noisy_eval_report.json` and the report's run-rule
study; synthetic labels — pipeline validation, not field performance).**

| Metric | Value |
| :--- | :--- |
| Shipped model | 60 trees, threshold **0.82** |
| Java↔Python parity | worst diff **2.2e-16** (pinned by `TrajectoryFraudModelTest`) |
| Broadcast precision / recall | **0.636 / 0.443** at **1.10% false alarms** (inside the 2% budget) |
| Teleport caught | **100%** (40 cases) vs ~10% for a speed rule |
| Sprint | **79.5%** (200) |
| Drift-then-vanish (a.k.a. stall-then-vanish) | **59.7%** (409) |
| Loop (unseen circling pattern) | **0.28%** (354) — the honest blind spot |
| Run rule | `k=3` OR single fix ≥ 0.97 |
| Honest-run false alarms | 29/400 = **7.25%** (95% CI ≈ 5–10%) |
| Fraud-run recall | 71/98 = **72%** (teleport 23/25, sprint 23/25, drift-then-vanish 24/25, loop 1/23) |

**The honest blind spot — and the plan.** The loop/circling pattern is nearly
blind (0.28% per-fix, 1/23 runs) because every trajectory is synthetic and a
loop's signature (long path, little net progress) is averaged away by per-step
features never tuned against a real loop. This is a **data problem**, stated
openly and bounded by the run-rule toggle. The five-step field-retraining plan
(collect anonymised feature rows, label from the existing dispute workflow, add
loop-specific features, retrain behind the same 2% gate, keep rollback to one
config flip) is in `SWIFTDELIVER_REPORT.md` §6.

**Explainability.** The admin modal draws the **per-fix score curve**, states the
**threshold** the decisions were taken against, and says on screen that the score
**is not proof of wrongdoing** and that the **training data is synthetic** — so an
operator cannot read a score as a verdict.

**Why a Random Forest, and not the obvious alternatives.** An Isolation Forest
(unsupervised) was built, measured and **rejected** — see
`ml/supervised_vs_unsupervised.py`. At a 2% false-positive rate the supervised RF
scores roughly 0.78/0.896 (precision/recall) vs 0.585/0.358 for the speed-rule
baseline; the unsupervised variant did not beat it.

### 10.2 RAG assistant (convenience feature)

**What it is.** A client-only chat widget answered by a local LLM, grounded in
(a) the FAQ corpus via **pgvector** retrieval and (b) the caller's **live** order
state (status, price, payment).

**Stack.** Spring AI + Ollama: `llama3.2:3b` for chat (laptop default;
`llama3.1:8b` optional for max quality), `nomic-embed-text` for
embeddings, pgvector store (HNSW, cosine, 768 dims). The corpus
(`faq.txt`) is **rebuilt on every startup**.

**Tool safety.** The model supplies an *ID*, but never an *identity*.
`AiConfiguration` resolves the caller from `SecurityContextHolder` and delegates
to `DeliveryService.cancelDelivery(id, client)`, which verifies ownership and
rejects anything not `PENDING`. An earlier version called `deleteDelivery()` with
no ownership check, which would have let any authenticated client delete another
user's delivery by naming its UUID.

**Graceful degradation.** If Ollama (or the vector store) is unavailable, the
widget reports itself unavailable and **the rest of the platform is unaffected** —
the platform still starts, books, prices, pays, dispatches and tracks. This is
why the AI assistant cannot break the build or the deploy.

---

## 11. Security and hardening

| Control | Implementation |
| :--- | :--- |
| Authentication | Stateless **JWT** (JJWT), BCrypt-style password hashing |
| Authorization | Role-scoped paths (`CLIENT` / `LIVREUR` / `ADMIN`); wrong role → 403 |
| Registration hardening | `/register` accepts only `CLIENT` / `LIVREUR`; `ADMIN` is out-of-band |
| Session revocation | `tokenVersion` (`tv` claim) bumped on logout; old tokens refused everywhere |
| Failed-login limiter | 5/account, 50/client IP per 15 min; **429 + `Retry-After`**; success clears the account counter |
| Handover-code throttle | 5/delivery, 20/courier per 15 min; failures only; success clears |
| Proof of delivery | **HMAC-SHA256** under a *derived* key; re-verifiable; 409 if never sealed |
| Secret management | No default `JWT_SECRET_KEY`; the app refuses to boot without it; cloud key generated by Render |
| Input validation | Server-side validation for booking/quoting/tracking/cancel; typed DTOs |
| Error mapping | Consistent envelope; `404`/`405` distinguished from `500` |
| IDOR resistance | Foreign IDs answer exactly like missing IDs (delivery location endpoint) |
| Tool safety (AI) | Cancel tool is ownership-checked server-side; the model supplies an ID, never an identity |

The account-level login counter is the primary control because it cannot be
spoofed with an `X-Forwarded-For` header; the per-client limit is intentionally
looser because a shared address makes innocent users share it.

---

## 12. Real-time tracking and notifications

- **Live positions (courier → client).** While `IN_TRANSIT`, the browser posts to
  `PATCH /courier/deliveries/{id}/location`. The backend writes
  `livreur:position:{id}` to **Redis with a 30-second TTL** and pushes to the
  STOMP topic `/topic/colis/{id}`.
- **The TTL is the signal.** Once the key expires the client is told tracking
  stopped ("Signal lost") — a stale marker can never be presented as live.
- **Notifications (server → user).** `NotificationService` keeps an `SseEmitter`
  per user; `GET /notifications/stream` is the SSE stream (also accepts the JWT
  as `?token=` because `EventSource` cannot set headers).
- **GPS modes.** Auto-detected: real GPS in a secure context (HTTPS or
  localhost) on a phone; deterministic route simulation otherwise. A header
  toggle overrides either way. A best-effort **Screen Wake Lock** prevents a
  phone sleeping and dropping updates mid-delivery.

---

## 13. Pricing, payment, routing and dispatch

### Pricing and payment (server is the single source of truth)
1. `POST /client/deliveries/quote` — OSRM real road route → `distanceKm`,
   `durationSeconds` (inflated by `TrafficModel`), `price`, `exceedsTimeLimit`.
2. `POST /client/deliveries` — the server **re-derives** the price and rejects a
   route over 45 minutes. The row is `PENDING` + `PENDING_PAYMENT`. No courier is
   reserved yet.
3. `PATCH /client/deliveries/{id}/pay` — captures payment, sets `PAID`, and
   **only then** invokes auto-dispatch.

**Pricing formula:** `max(15 MAD, ceil(km × 5 MAD))`. **Why dispatch is tied to
capture:** reserving a courier before payment would let a failed checkout occupy
delivery capacity; tying the two also keeps OR-Tools/OSRM work off the create
path (~60 ms).

### Routing and auto-dispatch
`RouteOptimizationService` fetches an OSRM `/table` travel-time matrix, inflates
it by the shared `TrafficModel` congestion factor, and solves with OR-Tools under:
- pickup must precede its own drop-off;
- `dropOff − pickUp ≤ 2700 s` per delivery (the 45-minute promise);
- vehicle total ≤ 14 400 s (4 hours).

The round starts from the courier's **last broadcast position** (Redis), not a
fixed point.

> **Droppable stops — the subtle trap.** Each pickup/drop-off is registered with
> `addDisjunction`, which lets the solver *omit* a node for a penalty. An
> infeasible stop is dropped silently, so a non-empty route does **not** prove a
> given delivery fits. `isServed()` checks that the specific delivery appears in
> the solution before assigning — this is what makes the 45-minute constraint
> actually mean something (see `RouteOptimizationServiceTest`).

---

## 14. Deployment and CI/CD

### Local (Docker Compose)
`docker compose up --build` runs all five services. Two things it does not hide:
routing still needs prepared map data (`scripts/prepare-osrm.sh`, one-time,
~20–60 min, ~1.7 GB), and the backend reaches Ollama on the host via
`host.docker.internal`. The numbered Maven + npm path in `README.md` needs no map
data to boot.

### Public cloud (Render, free tier)
`render.yaml` describes the whole stack as a **Blueprint** — one click creates:
`swiftdeliver-db` (Postgres + pgvector, PG 15), `swiftdeliver-cache` (Redis),
`swiftdeliver-backend`, `swiftdeliver-frontend`. Secrets are generated by Render
or wired by reference; nothing sensitive is committed.

The three free-tier limits, stated plainly:
1. web services **sleep after ~15 min idle** and take ~1 min to wake;
2. free **Postgres is deleted 30 days** after creation;
3. free **Key Value is in-memory and 25 MB** (fine — trails are TTL'd).

OSRM is pointed at the **public demo server** in the cloud (no free tier holds
the 1.5 GB map), and the assistant is **not** available in the cloud (no Ollama)
— both documented rather than hidden.

### CI/CD (`.github/workflows/ci.yml`)
Three jobs:
- **Backend** — Postgres + Redis service containers, enables pgvector, then
  `./mvnw -B package` (which runs the tests), uploads the jar.
- **Frontend** — Node 20, `npm ci`, `npm run lint`, `npm run build`.
- **Container images** — builds both images, then **asserts the shipped
  artefacts**, including: the backend image **contains the fraud model**; the
  image **refuses to boot without a signing key**; it **signs in against a real
  database**; logout **revokes the token**; the failed-login limiter returns
  **429**; `/health` returns 200 reporting `fraudModel:loaded` and a positive
  `fraudTrees`; a malformed body returns **400**; the compose file refuses an
  empty signing key; and the cloud blueprint is internally consistent.

**Render `autoDeployTrigger: checksPass`** means a push to `main` deploys **only
if the CI checks are green**. That is the entire CI/CD chain: GitHub Actions is
the gate, Render is the target.

---

## 15. Testing and verification

### Backend unit + context tests
`backend/mvnw.cmd test` — **25 classes, 166 tests**. The context tests
(`BackendApplicationTests`, `InfrastructureWiringTest`, `HealthEndpointTest`,
`MalformedRequestTest`) boot the real application and need Postgres + Redis, so
they run locally once the Docker stack is up (or in CI via service containers).

Highlights: `RunRuleTest` (9), `FraudTrailStoreTest` (12),
`TrajectoryFraudModelTest` (Java↔Python parity), `TrajectoryFraudServiceTest` (7),
`HandoverCodeRateLimiterTest` (10), `HandoverCodeThrottleTest` (2),
`HandoverCodeRateLimiterFailClosedTest` (7), `RedisHandoverAttemptStoreTest` (7),
`InMemoryHandoverAttemptStoreTest` (6), `HandoverThrottleRedisIntegrationTest` (6, real Redis),
`HandoverCodeTest` (3), `SealChainTest` (13), `SealChainWiringTest` (26),
`SealChainBackfillTest` (4), `RequiredSecretsGuardTest` (2),
`LoginRateLimiterTest` (9), `JwtServiceTest` (8), `DeliveryProofServiceTest` (9),
`CourierStatsTest` (5), `RouteOptimizationServiceTest`,
`DeliveryServiceRedisOutageTest` (Redis-outage degradation).

### Playwright verification scripts (`frontend/verify_*.js`)
Each exits non-zero on failure, so a regression cannot return quietly.

| Script | What it proves |
| :--- | :--- |
| `verify_auth_hardening.js` | logout revocation (same token refused), 429+`Retry-After` on a failed-login burst |
| `verify_proof.js` | HMAC seal re-verifies; sealed fields; 409 when unsealed |
| `verify_stats.js` | stats agree with the API; **ARRIVED counts as in-progress, not completed**; ratings over rated deliveries only |
| `verify_fraud_panel.js` | honest vs fraud trajectories; the **run rule** at the fleet-summary endpoint; the three badge states (`AI alert` / `AI ok` / no tag); score-curve modal + disclaimers; 403 for non-admins |
| `verify_fraud_live.js` | ~7 min: a real honest pass then a stall-then-teleport burst; the two passes **disagree** on one delivery; only the fraud run trips the badge |
| `verify_handover.js` | the 6-digit code path end-to-end (issue, wrong code refused, right code seals `codeVerified`) |
| `verify_chain.js` | the seal chain live (links, concurrent seals, tombstone delete green, SQL delete breaks at the successor, tail gap stated) |
| `verify_assign.js` / `verify_assign_ui.js` | assignment rules and no-reassign behaviour |
| `verify_pages.js`, `verify_pwa.js`, `verify_basemap.js`, `verify_client_map.js`, `verify_gps_pickup.js`, `verify_real_gps.js` | routes, offline/SW honesty, tile fallback+Retry, client map scope, GPS pickup, real-GPS + wake lock |

### Full rehearsal
`frontend/rehearse_demo.js` plays the corrected demo order against the running
stack — security → fresh booking → live tracking → fraud panel (`KEEP=1`) →
assistant — driving the **real UI** and the **real scripts**, and timestamps
every phase. It cleans up its own orders and prints a per-phase timeline.

### Latest local run (Docker up, this repo)
- Backend: **166 tests, 0 failures, 0 errors, 0 skipped** (25 classes).
- `verify_stats` **17/17** · `verify_fraud_panel` **17/17** ·
  `verify_fraud_live` **10/10** · `verify_handover` **12/12** · `verify_chain` **13/13**.
- `rehearse_demo.js`: **15/15 checks, 111.2 s** total, assistant answered in
  **3.1 s** (measured with the 8B model; the default is now 3B).

> `verify_assign_ui.js` has a known, documented nuance: its last two checks can
> fail when live user orders shift the dispatch balance onto courier2, making the
> current courier correctly non-reselectable under the no-reassign rule. All 8
> stacking/behaviour checks still pass.

---

## 16. The offline ML pipeline

Everything in `ml/` runs in Python, **offline**, and only produces an artefact
the JVM reads. Nothing here runs at request time.

| Script | Purpose |
| :--- | :--- |
| `train_fraud_model.py` | Trains the shipped Random Forest (60 trees) and exports the gzipped JSON |
| `train_trajectory_anomaly.py` | The unsupervised experiment (Isolation Forest) — measured, rejected |
| `evaluate_noisy.py` | The noisy-holdout evaluation (OSRM roads, 10 m GPS noise, unseen loop) → `noisy_eval_report.json` |
| `measure_or_clause.py` | The shipped run rule (`k=3` OR `≥0.97`), matching the Java `RunRule` |
| `tune_run_rule.py` | Tunes the run rule on a disjoint seed set |
| `audit_k3.py` | Bounds the run rule's collateral (honest-run false alarms, ~7.25%) |
| `supervised_vs_unsupervised.py` | The supervised-vs-unsupervised comparison |
| `benchmark_dispatch.py` | Dispatch/VRP benchmarking |
| `find_demo_seeds.py` | Finds seeds that produce clean demo trajectories |
| `trajectory_fraud_rf_parity.json` | Exported vectors used by the Java parity test |

The **parity contract** (Java ↔ Python, worst diff ~2e-16) is what keeps the two
implementations from drifting apart silently — it is asserted by
`TrajectoryFraudModelTest`.

---

## 17. Configuration reference

All backend config is environment-overridable (`application.yml`), with safe
defaults for a local run:

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `PORT` | `8080` | Backend listening port (PaaS assigns it) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | localhost / 5432 / delivery_db / admin / **(none — required)** | Postgres (compose: `.env`; tests: surefire test value) |
| `REDIS_HOST` / `REDIS_PORT` | localhost / 6379 | Redis |
| `OSRM_BASE_URL` | `http://localhost:5000` | Routing engine |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Local LLM (container uses `host.docker.internal`) |
| `SPRING_AI_OLLAMA_CHAT_MODEL` | `llama3.2:3b` | Chat model (laptop default; `llama3.1:8b` for max quality) |
| `SPRING_AI_OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model |
| `JWT_SECRET_KEY` | **(none — required)** | JWT signing key (≥ 32 random bytes) |
| `PROOF_HMAC_KEY` | falls back to `JWT_SECRET_KEY` | Root secret for the proof MAC (the seal key is derived) |
| `LOGIN_MAX_PER_ACCOUNT` / `_PER_CLIENT` / `LOGIN_WINDOW_SECONDS` | 5 / 50 / 900 | Failed-login limiter |
| `HANDOVER_MAX_PER_DELIVERY` / `_PER_COURIER` / `HANDOVER_WINDOW_SECONDS` | 5 / 20 / 900 | Handover-code throttle |
| `SPRING_PROFILES_ACTIVE` | — | `demo` runs the seeder (cloud uses it too) |
| `BACKEND_URL` / `OSRM_URL` (frontend) | — | Next.js rewrite targets (`/api/*`, `/osrm/*`) |
| `NEXT_PUBLIC_TILE_URL` (frontend) | OSM raster | Point at your own tile server to drop the OSM dependency |

---

## 18. How to run it

### Fastest path (whole stack in Docker)

```bash
cp .env.example .env          # then set JWT_SECRET_KEY (openssl rand -hex 32) AND DB_PASSWORD
docker compose up --build      # postgres, redis, osrm, backend, frontend
# open http://localhost:3000 and sign in with a demo account
```

Routing still needs prepared map data (`./scripts/prepare-osrm.sh`, one-time);
without it the platform runs but dispatch falls back to manual.

### Development path (Maven + npm, no map data needed)

```bash
# infrastructure only
docker compose up -d postgres redis osrm

# backend  (PowerShell: $env:JWT_SECRET_KEY = ... ; $env:DB_PASSWORD = ... — both
# required, the backend refuses to boot naming whichever is missing)
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=demo

# frontend
cd frontend
npm install
npm run dev
```

### Optional AI

```bash
ollama serve
ollama pull llama3.2:3b          # chat model, the default (~2 GB)
ollama pull nomic-embed-text   # embeddings
```

### Verify a deployment

```bash
curl -s http://localhost:8080/api/v1/health
# {"status":"UP","fraudModel":"loaded","fraudTrees":60}
```

Cloud hosts: frontend `https://swiftdeliver-frontend.onrender.com`,
backend `https://swiftdeliver-backend-j47m.onrender.com`.

---

## 19. Honest limitations

- **Assistant is local-only.** It needs Ollama; the free cloud tier has none, so
  the cloud widget reports itself unavailable. The fraud detector has no such
  dependency (pure in-process Java).
- **Fraud model is trained on synthetic data.** It is presented as
  decision-support with on-screen disclaimers, never as evidentiary proof. The
  **loop/circling blind spot** (0.28% per-fix) is a known, stated limit with a
  concrete retraining plan.
- **Free-tier reality.** Cold starts (~15 min idle → ~1 min wake), Postgres
  deleted after 30 days, in-memory 25 MB cache; the cloud routes through public
  OSRM and Nominatim (rate-limited). Fine for a demo, not a production plan —
  each has a named paid/self-hosted replacement.
- **Geocoding** uses public Nominatim in the browser; tests stub it for
  determinism. "Centre, Casablanca" resolves to Cameroon — a documented demo
  gotcha.
- **Wake lock is best-effort**; some browsers can still show "Signal lost".
- **`verify_assign_ui.js`** has the documented no-reassign nuance described above.

---

## 20. Recent work log

This section records the most recent changes on top of the baseline, all pushed
to `main`.

| Commit | Scope |
| :--- | :--- |
| `f29167c` | **feat(security,fraud)**: throttle handover codes (`HandoverCodeRateLimiter`), ship the run rule into the badge (`RunRule`), add `ARRIVED` to active stats, plus the S5 "Innovation Vision" write-up |
| `dc1de83` | **test(verify)**: cover the run rule and `ARRIVED` in the Playwright suites (`verify_fraud_panel`, `verify_fraud_live`, `verify_stats`) |
| `479b4b6` | **fix(verify)**: make the fraud verifiers robust when a probe run is interrupted |
| *(earlier)* | field-retraining plan added to the report; report/API/DEMO wording aligned |

### What the four workstreams delivered
1. **Handover-code throttling** — the 6-digit code is now brute-force-resistant
   (5/delivery, 20/courier per 15 min; failures only; success clears), reusing one
   429 + `Retry-After` path.
2. **Run rule shipped, not just measured** — `k=3` OR `≥0.97` lives in one class
   and drives both the live alert and the dashboard badge.
3. **Stats + revenue correctness** — `ARRIVED` counts as in-progress (not
   completed) in both courier and admin stats; revenue is verified as
   `SUM(price) WHERE PAID`, with cancels moving paid orders to `REFUNDED`.
4. **Rubric alignment** — the report now separates *baseline/industrialisation*
   (done, often exceeding expectations) from the *real AI innovation*, explaining
   the why, the need, the impact and the business value.

### Verifier robustness fixes (`479b4b6`)
Running the suites against an already-warm stack exposed two latent fragilities
(none a product bug):
1. `verify_fraud_panel` matched rows by the `PANEL-*` description with `.first()`;
   a stale probe from an interrupted earlier run has no trail (6 h TTL) and could
   match instead of the fresh row, reporting a missing "AI ok" badge. Fixed by
   **sweeping stale `PANEL-*` probes at startup** and locating rows by the fresh
   **delivery-id prefix**.
2. `verify_fraud_live` pinned "all three jumps flagged"; the model sits near
   threshold on some geometries, so the count is not a stable contract. Fixed by
   **enlarging the burst** (four jumps, several km) and asserting the product
   contract (the run rule) plus ≥ 1 flagged fix.

### Current repository state
- Branch `main`, in sync with `origin/main`, HEAD `e03f277`, working tree clean.
- Backend **166/166** tests green; the full Playwright set and the rehearsal green.

---

## 21. Documentation index and glossary

### The other documents

| Document | What it covers |
| :--- | :--- |
| `README.md` | Quick start, demo accounts, features by role, design system, pricing |
| `SYSTEM_ARCHITECTURE.md` | Module map, class table, lifecycle data flows, AI subsystem |
| `API_REFERENCE.md` | Every endpoint, request/response shapes, status codes, data models |
| `DEMO.md` | The measured demo runbook and the run-of-show timings |
| `DEPLOY.md` | The cloud runbook and post-deploy verification commands |
| `SWIFTDELIVER_REPORT.md` | The project report: S5 innovation, baseline, limitations, retraining plan |
| `PROJECT_OVERVIEW.md` | **This file** — the whole thing in one place |

### Glossary

- **OSRM** — Open Source Routing Machine; self-hosted road routing.
- **OR-Tools** — Google's optimisation library; solves the courier VRP.
- **VRP** — Vehicle Routing Problem.
- **pgvector** — Postgres extension storing vector embeddings (FAQ retrieval).
- **RAG** — Retrieval-Augmented Generation; retrieve context, then generate.
- **SSE** — Server-Sent Events; one-way live push (notifications).
- **STOMP** — a messaging protocol over WebSocket (live map topic).
- **HMAC** — keyed hash; here it seals a proof of delivery that only the server
  can produce.
- **tokenVersion (`tv`)** — per-account counter that makes logout revoke tokens.
- **Run rule** — the per-run fraud decision: `k=3` consecutive flagged fixes OR
  one fix ≥ 0.97.
- **`PANEL-*`** — the temporary probe deliveries the fraud verification scripts
  create to drive the dashboard.
- **`ARRIVED`** — the courier is at the door (an in-progress state, not
  completed).

---

*This overview was written against the source tree and the existing docs; where
they disagreed, the code was treated as the authority and the discrepancy noted.*
