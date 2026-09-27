# SwiftDeliver System Architecture

The architectural reference for the SwiftDeliver codebase. Every class and path
named below exists in the tree; where behaviour is surprising or a constraint is
easy to break, the reason is stated inline.

Package root: `backend/src/main/java/com/delivery/backend/`

---

## 1. Overview

SwiftDeliver is a modular monolith: a single Spring Boot deployable with an
internally decoupled, role-oriented package structure, and a fully separate
Next.js frontend. Infrastructure is containerised: PostgreSQL (with pgvector),
Redis, and OSRM.

Three roles, three workspaces:

*   **Clients** — book deliveries, pay, track live, rate the courier, and use the assistant.
*   **Couriers** — work an assigned round, advance delivery status, broadcast position.
*   **Admins** — oversight, metrics, live fleet map, manual intervention.

Nothing in the request path depends on a paid third-party API. Routing (OSRM),
embeddings and the language model (Ollama) all run locally, and fonts are
self-hosted. The browser reaches the backend and the routing engine only through
the Next.js origin (`/api/*` and `/osrm/*` rewrites), so no host:port is hard-coded
in client code and OSRM is never exposed publicly.

---

## 2. Module decomposition

### 2.1 `modules/auth/` — identity
Registration, stateless JWT issue/validation, profile management.

| Class | Role |
| :--- | :--- |
| `AuthController` | `/auth/register`, `/auth/login`, `/auth/verify` |
| `AuthService` | Registration rules, credential check |
| `JwtService` | Token issue and validation |
| `User` / `UserRepository` | JPA entity; **implements `UserDetails`**, so the principal is the entity itself |
| `UserController` | `/users/me` |
| `AdminUserController` | `/admin/users`, `/admin/users/couriers` |
| `ResendEmailService` | Verification email delivery |

> **Registration hardening.** `AuthService.register()` rejects `ADMIN` outright.
> The role arrives in the request body, so without that guard any anonymous
> caller could `POST {"role":"ADMIN"}` and authenticate as an administrator.
> Self-service signup is limited to `CLIENT` and `LIVREUR`.
> `UserController.updateProfile()` assigns a fixed field whitelist and never
> touches `role`, so it is not a second escalation path.

### 2.2 `modules/delivery/` — the core domain
Delivery lifecycle, pricing, payment, and routing.

| Class | Role |
| :--- | :--- |
| `Delivery` / `DeliveryStatus` | Entity and its operational state enum |
| `PaymentStatus` | The independent payment axis (see §4) |
| `DeliveryService` | Orchestration: create, quote, capture, cancel, rate, **auto-dispatch** |
| `PricingService` | OSRM-quoted pricing and the 45-minute admission check |
| `TrafficModel` | The single shared congestion model |
| `RouteOptimizationService` | OSRM travel-time matrix → OR-Tools VRP solve |
| `ClientDeliveryController` | Client-facing endpoints |
| `CourierDeliveryController` | Courier-facing endpoints |
| `AdminDeliveryController` | Admin-facing endpoints |
| `dto/` | Request/response contracts |

Controllers are split per role; all business logic lives in `DeliveryService`.

### 2.3 `modules/notification/` — real-time messaging
`NotificationService` maintains an `SseEmitter` per connected user and pushes on
state changes. `NotificationController` exposes the SSE stream plus read/unread
endpoints. `EventSource` cannot set an `Authorization` header, so the stream
also accepts the JWT as a `?token=` query parameter — the one place a token
travels in a URL.

### 2.4 `modules/ai/` — local RAG assistant
| Class | Role |
| :--- | :--- |
| `ChatService` | Retrieval + prompt assembly + LLM call |
| `AiConfiguration` | The cancel-order tool exposed to the model |
| `ChatController` | `POST /client/chat` |

`ChatService` retrieves from pgvector, injects the caller's **live** order state
into the system prompt, and calls a local model through Spring AI Ollama. The
corpus is `src/main/resources/faq.txt`, rebuilt into the vector store on every
startup.

> **Rebuild rather than load-if-empty.** If the FAQ source is edited or
> translated while the store already holds chunks, a load-if-empty check keeps
> serving the old version and mixes it with the new one. The corpus is small, so
> it is cleared and re-added every boot.

> **Tool safety.** The model supplies an *ID*; it never supplies an identity.
> `AiConfiguration` resolves the caller from `SecurityContextHolder` and
> delegates to `DeliveryService.cancelDelivery(id, client)`, which verifies
> ownership and rejects anything not `PENDING`. The model is never trusted to
> decide who is asking. An earlier version called `deleteDelivery()` with no
> ownership check at all, which meant any authenticated client could have had
> another user's delivery deleted by naming its UUID.

### 2.5 `config/` — cross-cutting
`SecurityConfig` (role-scoped paths), `JwtAuthenticationFilter`, `ApplicationConfig`
(password encoder, `UserDetailsService`), `RedisConfig`, `WebSocketConfig` and
`WebSocketAuthInterceptor` (JWT on the STOMP `CONNECT` frame).

---

## 3. Code-grounded functionality directory

| Functionality | Implementation |
| :--- | :--- |
| JWT issue / validate | `auth/JwtService.java` |
| Registration (role-restricted) | `auth/AuthService.java` → `register()` |
| Profile update (whitelisted fields) | `auth/UserController.java` → `updateProfile()` |
| Price a delivery | `delivery/PricingService.java` → `quote()` |
| Admission check (45 min) | `delivery/PricingService.java` → `quote()` |
| Create order (`PENDING_PAYMENT`) | `delivery/DeliveryService.java` → `createDelivery()` |
| Capture payment + dispatch | `delivery/DeliveryService.java` → `capturePayment()` |
| Auto-dispatch / VRP assignment | `delivery/DeliveryService.java` → `autoDispatch()` |
| Fit verification after solving | `delivery/DeliveryService.java` → `isServed()` |
| OSRM travel-time matrix | `delivery/RouteOptimizationService.java` → `fetchTimeMatrix()` |
| VRP solve | `delivery/RouteOptimizationService.java` → `optimizeDeliveries()` |
| Shared congestion factor | `delivery/TrafficModel.java` → `currentFactor()` |
| Status transitions | `delivery/DeliveryService.java` → `updateDeliveryStatus()` |
| GPS broadcast / Redis cache | `delivery/DeliveryService.java` → `updateCourierLocation()` |
| Cancellation + refund/void | `delivery/DeliveryService.java` → `cancelDelivery()` |
| Rating | `delivery/DeliveryService.java` → `rateDelivery()` |
| Revenue | `delivery/DeliveryRepository.java` → `getTotalRevenue()` |
| Platform metrics | `delivery/DeliveryService.java` → `getAdminStats()` |
| Activity feed | `delivery/DeliveryService.java` → `getAdminActivities()` |
| SSE stream | `notification/NotificationController.java` → `stream()` |
| RAG retrieval + prompt | `ai/ChatService.java` → `chatWithClient()` |
| Cancel tool (ownership-checked) | `ai/AiConfiguration.java` → `cancelDeliveryFunction()` |
| Error envelope | `exception/GlobalExceptionHandler.java` |

---

## 4. Data flow: the delivery lifecycle

The lifecycle has **two independent axes**. Delivery status tracks operations;
payment status tracks money. They are deliberately separate so an unpaid or
failed order is representable without being counted as revenue.

### 4.1 Booking (client)

1.  **Quote** — `POST /client/deliveries/quote`. `PricingService` calls OSRM
    `/route/v1/driving` for the two endpoints, inflates the duration by the
    shared `TrafficModel` factor, and returns `max(15 MAD, ceil(km × 5 MAD))`
    plus whether the route breaks the 45-minute limit. Nothing is persisted.
2.  **Create** — `POST /client/deliveries`. The server re-derives the price
    itself and rejects a route over 45 minutes. The row is written with
    `status = PENDING` and `paymentStatus = PENDING_PAYMENT`. **No courier is
    reserved yet.**
3.  **Capture** — `PATCH /client/deliveries/{id}/pay`. On success the order
    becomes `PAID` and only *then* is auto-dispatch invoked.

> **Why dispatch is tied to capture.** Reserving a courier before payment would
> let a failed checkout occupy delivery capacity. Tying the two also keeps the
> OR-Tools/OSRM work off the create path, which returns in ~60ms.

### 4.2 Auto-dispatch

`DeliveryService.autoDispatch()` walks candidate couriers in turn. For each:

1.  Collect their `ASSIGNED` + `IN_TRANSIT` deliveries and append the new order.
2.  Start the round from the courier's **last broadcast position** (Redis), not a
    fixed point — previously every courier was assumed to sit at one hard-coded
    Casablanca coordinate, which made the routing decision independent of where
    anyone actually was.
3.  `RouteOptimizationService` fetches an OSRM `/table` matrix, inflates it by
    the congestion factor, and solves with OR-Tools under these constraints:
    * pickup must precede its own drop-off;
    * `dropOff − pickUp ≤ 2700s` per delivery (the 45-minute promise);
    * vehicle total ≤ 14 400s (4 hours).
4.  **Verify the specific delivery is in the solution** (`isServed()`), then
    assign and notify.

> **Droppable stops — the subtlest part of this module.** Each pickup and
> drop-off is added with `addDisjunction`, which does *not* force a node to be
> served; it lets the solver omit it for a penalty. An infeasible stop is
> therefore dropped silently rather than reported as an error, and a non-empty
> route is returned containing only the courier's *other* work. Checking
> `!orderedWaypoints.isEmpty()` is therefore insufficient and was the reason the
> 45-minute constraint did nothing. `isServed()` is what makes the constraint
> meaningful. See `RouteOptimizationServiceTest`.

### 4.3 Live tracking

While `IN_TRANSIT`, the courier's browser posts position to
`PATCH /courier/deliveries/{id}/location`. The backend writes
`livreur:position:{id}` to Redis with a **30-second TTL** and pushes to the
STOMP topic `/topic/colis/{id}`. The TTL is the signal: once the key expires the
client is told tracking stopped, so a stale marker can never be presented as live.

### 4.4 Completion and rating

The courier advances status to `DELIVERED`. The client may then rate the order
once. Ratings are never generated by the system — the platform reports the
average of what real customers submitted, including zero when nobody has rated.

---

## 5. Notable implementation decisions

**Self-hosted routing.** OSRM in Docker removes per-call cost and keeps customer
coordinates inside the deployment. The routing engine is reached only from the
backend and through the Next.js proxy.

**One congestion model.** `TrafficModel` is read by both pricing and dispatch.
It previously existed as two identical copies; if either had been edited, quotes
and dispatch would have silently disagreed about what 45 minutes means, and the
symptom would be couriers assigned rounds that break the quote the customer was
given.

**No fabricated data.** Delivery ratings come only from real customer
submissions. A previous startup routine assigned random ratings to unrated
delivered orders, which made the headline rating metric meaningless and rejected
genuine reviews as "already rated".

**Distinguishable failures.** `GlobalExceptionHandler` maps unmapped paths to
`404` and wrong verbs to `405` rather than letting the catch-all report `500`.
A mistyped endpoint otherwise looks identical to a genuine server fault, which
is exactly what allowed a wrong-path bug in the admin map to go unnoticed.

**Dependency note.** OR-Tools ships its native solver per platform. `ortools-java`
supplies the Java API plus the Linux and macOS binaries transitively; the
Windows binary is added by an OS-activated Maven profile, so a single `pom.xml`
builds everywhere.

---

## 6. Frontend structure

`frontend/src/`

```
app/            routes: /, /login, /register, /dashboard/{,deliveries,notifications,profile}
components/     shared UI (see below)
context/        AuthContext — JWT storage and session state
lib/api.js      the single fetch wrapper; every backend call goes through it
hooks/
```

Shared components: `AuthShell`, `Sidebar`, `Navbar`, `ProtectedRoute`, `Modal`,
`Toast`, `StatusPill`, `StopAction`, `EmptyState`, `PaymentModal`,
`RatingModal`, `TrackingModal`, `OptimizedRouteModal`, `LocationPicker`,
`LocationPickerMap`, `ChatWidget`, `AdminMap`, `TrackingMap`,
`OptimizedRouteMap`.

`StatusPill` and `StopAction` exist so that status vocabulary and legal state
transitions are defined once. `StopAction` offers only the forward transition
valid from the current state; an earlier `<select>` allowed moving a delivery
backwards, which the domain does not permit.

The visual layer is a token system in `tailwind.config.js` consumed through
semantic class names in `globals.css` (`.surface`, `.btn-primary`, `.input`,
`.status-*`), so the theme is retunable in one file.
