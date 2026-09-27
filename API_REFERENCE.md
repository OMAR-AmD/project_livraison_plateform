# SwiftDeliver API Reference

REST API exposed by the Spring Boot backend.
Base URL: `/api/v1` — in the browser this is reached through the Next.js proxy
(`/api/*` is rewritten to the backend, see `frontend/next.config.mjs`).

**Authentication.** All endpoints except `/auth/register`, `/auth/login` and
`/auth/verify` require `Authorization: Bearer <token>`. Role-scoped paths return
`403` for a valid token with the wrong role.

**Errors.** Failures return a consistent envelope:

```json
{
  "status": 400,
  "error": "Invalid request",
  "message": "Human-readable explanation",
  "timestamp": "2026-09-27T18:42:11.123"
}
```

| Situation | Status |
| :--- | :--- |
| Validation failure | 400 |
| Missing / invalid JWT | 401 |
| Valid token, wrong role | 403 |
| Unknown ID, or a delivery belonging to someone else | 400 |
| Unmapped path | 404 |
| Wrong HTTP verb on a real path | 405 |
| Routing engine (OSRM) unreachable | 503 |

---

## 🔒 Authentication

### `POST /auth/register`
Creates an account. **Self-service signup may only create a `CLIENT` or a
`LIVREUR`.** A request for `ADMIN` is rejected with `400` — the role is
client-controlled, so accepting it would let anyone take over the platform.
Administrator accounts are granted out of band.

* **Body**:
  ```json
  {
    "email": "user@example.com",
    "password": "password123",
    "role": "CLIENT"
  }
  ```
  * `password` — 8–100 characters
  * `role` — required, one of `CLIENT` or `LIVREUR`

* **Response (201 CREATED)**: `String` confirmation message.

### `POST /auth/login`
Authenticates a user and returns a JWT.
* **Body**: `{ "email": "...", "password": "..." }`
* **Response (200 OK)**:
  ```json
  { "token": "eyJhbGciOiJIUzI1NiJ9...", "email": "user@example.com", "role": "CLIENT" }
  ```

### `GET /auth/verify?token=...`
Confirms an email-verification token. Tokens expire after 24 hours.

---

## 👤 User management
*Require `Authorization: Bearer <token>`.*

### `GET /users/me`
Profile of the authenticated user.

### `PUT /users/me`
Updates the profile. Accepts exactly `firstName`, `lastName`, `phoneNumber`,
`defaultAddress`, `avatarUrl` — the role is not among them, so this endpoint
cannot be used to escalate privileges.

---

## 👤 Client endpoints
*Require `ROLE_CLIENT`.*

### `GET /client/deliveries`
All deliveries belonging to the authenticated client, newest first.

### `POST /client/deliveries/quote`
**Prices a delivery without creating it.** The server calls OSRM for the real
road route and returns the figure the order will be charged. The client never
supplies a price; the value shown here is the value stored on creation.

* **Body**: same shape as `POST /client/deliveries`.
* **Response (200 OK)**:
  ```json
  {
    "distanceKm": 3.42,
    "durationSeconds": 812,
    "price": 30.0,
    "congestionFactor": 1.4,
    "exceedsTimeLimit": false,
    "maxDurationSeconds": 2700
  }
  ```
  Pricing is `max(15 MAD, ceil(km × 5 MAD))`. `durationSeconds` is the raw OSRM
  duration multiplied by the rush-hour factor from `TrafficModel`.
  `exceedsTimeLimit` is true when the route cannot be served inside 45 minutes.

### `POST /client/deliveries`
Creates the order. The server re-computes the price itself and rejects the
request if the route exceeds the 45-minute limit. The order is created as
`PENDING_PAYMENT`; it is **not** yet dispatched.

* **Body**:
  ```json
  {
    "description": "2 small boxes",
    "pickupAddress": "1 Rue Ibn Batouta, Casablanca",
    "dropoffAddress": "41 Boulevard d'Anfa, Casablanca",
    "pickupLat": 33.5731,
    "pickupLng": -7.5898,
    "dropoffLat": 33.5891,
    "dropoffLng": -7.6311
  }
  ```
  All four coordinates are **required** — the order cannot be priced without
  them. Addresses are free text and are not geocoded server-side.

* **Response (201 CREATED)**: `DeliveryDTO` with `status = PENDING` and
  `paymentStatus = PENDING_PAYMENT`.

### `PATCH /client/deliveries/{id}/pay`
**Captures payment.** This is the step that makes the order count as revenue
*and* the step that hands it to the auto-dispatcher. Only after this succeeds
does the delivery move to `ASSIGNED` and a courier get reserved — so a failed
checkout cannot occupy delivery capacity.

* **Response (200 OK)**: `DeliveryDTO` with `paymentStatus = PAID` and, if a
  courier was available, `status = ASSIGNED`.
* Returns `400` if the order is not awaiting payment, or does not belong to the
  caller.

### `PATCH /client/deliveries/{id}/cancel`
Cancels a delivery while it is still `PENDING`. Sets `paymentStatus` to `REFUNDED`
if money had been captured, or `VOIDED` if it had not. Rejects with `400`
otherwise, including when the delivery belongs to another client.

### `GET /client/deliveries/{id}/location`
Last broadcast courier position, read from Redis. Returns `404` once the key has
expired (30-second TTL), which is how the client detects that tracking stopped.

### `POST /client/deliveries/{id}/rate`
Rates a delivered order (`rating` 1–5, optional `reviewComment`). Rejected if
the delivery is not `DELIVERED`, is not the caller's, or has already been rated.

### `DELETE /client/deliveries/{id}`
Permanently removes one of the caller's own `DELIVERED` or `CANCELLED` orders.
Returns `204`.

### `POST /client/chat`
Sends a message to the local RAG assistant. Grounded in `faq.txt` plus the
caller's live order state. The model may invoke the cancel-order tool, which
is ownership-checked server-side.

* **Body**: `{ "message": "Where is my order?" }`
* **Response (200 OK)**: `{ "response": "..." }`

---

## 🚚 Courier endpoints
*Require `ROLE_LIVREUR`.*

### `GET /courier/deliveries`
Deliveries assigned to the authenticated courier, newest first.

### `PATCH /courier/deliveries/{id}/status`
Advances an assigned delivery. Valid targets: `ASSIGNED`, `IN_TRANSIT`,
`DELIVERED`. Rejected if the delivery is not assigned to the caller.

### `PATCH /courier/deliveries/{id}/location`
Broadcasts the courier's position for one delivery. Requires the delivery to be
assigned to the caller and `IN_TRANSIT`. Writes to Redis with a 30-second TTL
and pushes to the STOMP topic `/topic/colis/{id}`.

* **Body**: `{ "latitude": 33.5731, "longitude": -7.5898 }`

### `GET /courier/deliveries/optimize?lat=..&lng=..`
Solves the round for the courier's `ASSIGNED` + `IN_TRANSIT` deliveries.

* **Response (200 OK)**:
  ```json
  {
    "totalTimeSeconds": 1602,
    "routeLog": ["Start (Courier Location)", "Pickup: ...", "Dropoff: ...", "End"],
    "orderedWaypoints": [
      { "deliveryId": "uuid", "type": "PICKUP", "latitude": 33.57, "longitude": -7.58, "description": "...", "step": 1 }
    ]
  }
  ```

> **Note on droppable stops.** Each pickup and drop-off is registered with the
> solver as a *disjunction*, so the solver may omit a stop rather than report an
> infeasible set. A non-empty route therefore does **not** prove that a given
> delivery fits — callers must check that the delivery's own ID appears in
> `orderedWaypoints`. The auto-dispatcher does exactly this.

---

## 🛡️ Admin endpoints
*Require `ROLE_ADMIN`.*

### `GET /admin/deliveries`
Every delivery on the platform.

### `PATCH /admin/deliveries/{id}/assign`
Manually assigns a delivery to a courier. Exists for orders needing
intervention; normal assignment is automatic on payment capture.

* **Body**: `{ "courierId": "uuid-of-courier" }`

### `DELETE /admin/deliveries/{id}`
Removes a `DELIVERED` or `CANCELLED` delivery. Returns `204`.

### `GET /admin/deliveries/{id}/location`
Last known courier position for oversight. `204` if expired.

### `GET /admin/deliveries/couriers/{courierId}/route?lat=..&lng=..`
The optimised round for one courier, same solver as the courier endpoint.

### `GET /admin/deliveries/stats`
Platform metrics.
```json
{
  "totalUsers": 16, "totalDeliveries": 7, "completedToday": 0,
  "averageRating": 0.0, "activeDeliveries": 7, "totalRevenue": 177.0
}
```
`totalRevenue` sums orders whose `paymentStatus` is `PAID` only — orders awaiting
payment, voided orders and refunds are excluded.

### `GET /admin/deliveries/activities`
The ten most recent platform events, for the activity feed.

### `GET /admin/users`
All accounts, for the user-management table.

### `GET /admin/users/couriers`
Accounts with the `LIVREUR` role.

---

## 🔔 Notifications
*Require `Authorization: Bearer <token>`. `EventSource` cannot set headers, so
the SSE stream also accepts the token as a `?token=` query parameter.*

| Method | Path | Purpose |
| :--- | :--- | :--- |
| `GET` | `/notifications/stream` | SSE stream of live notifications |
| `GET` | `/notifications` | Historical list for the caller |
| `GET` | `/notifications/unread-count` | `{ "count": 5 }` |
| `PUT` | `/notifications/{id}/read` | Mark one read (`204`) |
| `PUT` | `/notifications/read-all` | Mark all read (`204`) |

---

## 📦 Data models

### `DeliveryDTO`
```json
{
  "id": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "description": "2 small boxes",
  "pickupAddress": "1 Rue Ibn Batouta, Casablanca",
  "dropoffAddress": "41 Boulevard d'Anfa, Casablanca",
  "status": "ASSIGNED",
  "clientEmail": "client@example.com",
  "courierEmail": "courier@example.com",
  "courierId": "uuid",
  "pickupLat": 33.5731, "pickupLng": -7.5898,
  "dropoffLat": 33.5891, "dropoffLng": -7.6311,
  "courierLatitude": 33.574,
  "courierLongitude": -7.590,
  "rating": null,
  "reviewComment": null,
  "price": 30.0,
  "paymentStatus": "PAID",
  "createdAt": "2026-09-27T18:42:11",
  "updatedAt": "2026-09-27T18:42:12"
}
```

`courierLatitude` / `courierLongitude` are populated only while the delivery is
`IN_TRANSIT` and a fresh position exists in Redis.

### State machines

**Delivery status** (`DeliveryStatus`) — the operational lifecycle:

```
PENDING ──► ASSIGNED ──► IN_TRANSIT ──► DELIVERED
   │                                                 
   └──────────► CANCELLED ◄──────────────────────────┘
```

`CANCELLED` is reachable from `PENDING` by the client, or by an administrator
from a terminal state. A `PENDING_PAYMENT` order can still be cancelled
because it has not yet been dispatched.

**Payment status** (`PaymentStatus`) — a separate axis, and the one revenue
depends on:

| Value | Meaning |
| :--- | :--- |
| `PENDING_PAYMENT` | Order created, money not yet captured |
| `PAID` | Captured. **The only state counted as revenue.** |
| `VOIDED` | Cancelled before any money was captured |
| `REFUNDED` | Cancelled after capture; money returned |

The two axes are independent by design: a delivered order is `DELIVERED` +
`PAID`, and a failed checkout is `PENDING` + `PENDING_PAYMENT` — representable
without inflating revenue.

### Coordinate reference

All example coordinates are in **Casablanca, Morocco**, which is the platform's
stated operating area (the FAQ documents coverage as Casablanca).
