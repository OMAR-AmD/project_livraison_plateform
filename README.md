# SwiftDeliver - Delivery Management Platform

SwiftDeliver is a modern, full-stack web application designed to manage package deliveries. It connects Clients, Couriers (Livreurs), and Administrators through a unified, role-based platform. 

The system uses **Artificial Intelligence (Google OR-Tools)** and **OSRM (Open Source Routing Machine)** for multi-stop route optimization, and **WebSockets** for real-time live map tracking.

---

## 🚀 Features by Role

### 👤 Client
- Request new deliveries via an interactive map (Address Auto-completion).
- **Simulated Payment Gateway** (Stripe-like secure checkout experience).
- Real-time GPS tracking on a map to see the Courier moving live.
- Rate the courier and leave reviews upon delivery completion.

### 🚚 Courier (Livreur)
- View a dedicated dashboard of assigned deliveries.
- **AI Route Optimization**: Automatically computes the mathematically optimal path to visit all pickup and drop-off points using Google OR-Tools.
- **Simulate Movement**: One-click broadcast of GPS coordinates along the optimized path via WebSockets for demonstration purposes.

### 🛡️ Admin
- Live Dashboard with platform analytics (Simulated vs Real numbers).
- Manage user roles and system settings.
- Real-time event feed of what is happening on the platform.

---

## 🏗️ Tech Stack

### Backend
- **Java 17** & **Spring Boot 3.3**
- **Google OR-Tools** (Vehicle Routing Problem AI)
- **Spring WebSockets / STOMP** (Real-time updates)
- **Spring Security + JWT** (Stateless authentication)
- **PostgreSQL** (Relational Database)
- **Redis** (Cache & Message Broker)

### Frontend
- **Next.js 14** (React 18, App Router)
- **Tailwind CSS** with a custom dark design system (see *Design system* below)
- **Leaflet & React-Leaflet** (interactive maps)
- **OpenStreetMap raster tiles**, darkened at the CSS layer so no keyed tile service is required

---

## 🎨 Design system

The interface is built from three ideas, defined once in `tailwind.config.js` and
consumed through semantic class names in `src/app/globals.css`:

- **One neutral ramp (`ink`)** carries the whole dark theme. Every surface is a
  deliberate step on that ramp rather than a default Tailwind colour.
- **One accent (`signal`)** — a hi-visibility orange reserved for the primary
  action *and* for the live/in-transit state, so colour always carries meaning.
- **Semantic aliases** (`surface`, `line`, `content`) mean components never
  reference a raw ramp step, so the theme can be retuned in one file.

Component classes: `surface`, `surface-raised`, `surface-interactive`, `btn-primary`
/ `-secondary` / `-ghost` / `-danger`, `label`, `input`, `status-*`, `badge-*`,
`stat-value`, `table-head`, `page-title`.

Fonts are **self-hosted** from `src/app/fonts` via `next/font/local`. There is no
`@import` of a remote font service at runtime.

### Dark map tiles without a paid basemap

The only basemap that needs no API key is the standard OpenStreetMap raster, which
is light. Rather than introduce a keyed (and metered) tile provider, the tile pane
is inverted and hue-rotated via CSS in `globals.css`, leaving markers and polylines
untouched so the accent colour stays accurate.

OpenStreetMap's tile server is a free public service whose usage policy
discourages exactly this kind of application use, so it throttles under load and
can return nothing at all. When that happens the map no longer fails silently:
every map shares one `BasemapLayer` component that

- substitutes a local fallback tile, so a broken basemap looks broken rather than
  like an empty grey rectangle with markers floating on it,
- shows a notice explaining that positions and routes are still accurate, with a
  working **Retry** button, and
- retries on a backoff so a throttled server is not hammered.

Point `NEXT_PUBLIC_TILE_URL` at your own tile server to remove the dependency
entirely — for example `/tiles/{z}/{x}/{y}.png` proxied to a container serving
tiles generated from the same OpenStreetMap extract that `scripts/prepare-osrm.sh`
already downloads. The template accepts the standard `{z}/{x}/{y}` placeholders,
so no code change is needed.

`frontend/verify_basemap.js` asserts this behaviour by blocking every tile request
and checking that the user is told and that Retry works. It exits non-zero if the
warning ever stops appearing, so the regression cannot come back quietly.


---

## ⚙️ How to Run Locally

### Prerequisites

| Requirement | Version | Notes |
| :--- | :--- | :--- |
| Docker + Docker Compose | any recent | Brings up PostgreSQL, Redis, OSRM |
| Java | 17 | Backend toolchain |
| Maven | 3.8+ | Or use the bundled `./mvnw` |
| Node.js | 18+ | Frontend |
| **Ollama** | any recent | **Only for the AI assistant — see below** |

### About Ollama (optional)

The customer assistant runs entirely on your machine: a local language model
served by [Ollama](https://ollama.com), with the FAQ embedded into pgvector by
a local embedding model.

**Ollama is optional.** If it is not running, the platform still starts and every
other feature works — booking, server-side pricing, payment capture, auto-dispatch,
live tracking and the admin dashboard. Only the chat widget reports itself
unavailable, and the startup log prints the commands needed to enable it.

To enable the assistant:

```bash
ollama serve                      # in its own terminal
ollama pull llama3.2:1b           # chat model
ollama pull nomic-embed-text      # embedding model for pgvector
```

The two model names are configured in `backend/src/main/resources/application.yml`
and can be overridden with `SPRING_AI_OLLAMA_CHAT_MODEL` and
`SPRING_AI_OLLAMA_EMBEDDING_MODEL`. The FAQ corpus is rebuilt into the vector
store on every startup, so editing `faq.txt` takes effect on the next boot.

### Seed demo data (optional)

To populate the platform with realistic accounts and orders in every state:

```bash
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=demo
```

The seeder is bound to the `demo` profile, so it can never run against a real
database by accident. It creates an administrator, couriers, clients, and
deliveries spanning pending / assigned / in-transit / delivered / cancelled.

**Default accounts created by the seeder** (password `password123`):

| Email | Role |
| :--- | :--- |
| `admin@swift.com` | `ADMIN` |
| `courier1@swift.com`, `courier2@swift.com` | `LIVREUR` |
| `client1@swift.com`, `client2@swift.com` | `CLIENT` |

> Administrator accounts cannot be created through `/register` — the role is
> client-controlled, so accepting it would let anyone take over the platform. The
> seeder grants it directly, or you can promote an account with SQL:
> ```sql
> UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';
> ```

---

### 1. Prepare Map Routing Data (OSRM)
The routing data is ~1.7 GB, so it is not committed to Git. You have to download
and compile it once, before starting the containers.

Run the preparation script (Git Bash, Linux or macOS):

```bash
./scripts/prepare-osrm.sh              # Morocco
./scripts/prepare-osrm.sh portugal     # any other Geofabrik region
```

It downloads the extract, runs `osrm-extract` → `osrm-partition` →
`osrm-customize` in Docker, and leaves the result in `osrm-data/`. Expect
20–60 minutes depending on your hardware, and several GB of disk. Nothing is
installed on your machine. It is idempotent: re-running it does nothing unless
you pass `--force`.

> **The three compile steps are all required.** `docker-compose.yml` starts the
> engine with `osrm-routed --algorithm mld /data/map.osrm`, so the data must be
> preprocessed for the multi-level Dijkstra algorithm. If you only run
> `osrm-extract`, the container will start but serve no routes.

Verify the engine before going further — a distance of `0` means OSRM is
answering but has no road network loaded:

```bash
curl 'http://localhost:5000/route/v1/driving/-7.5898,33.5731;-7.6311,33.5891?overview=false'
# -> {"code":"Ok", ... "distance": 5623.2, ...}
```

### 2. Start the Infrastructure (Database, Redis, OSRM)
Once the map data is compiled, spin up the Docker containers from the root directory:
```bash
docker-compose up -d
```

### 3. Start the Backend
The backend requires a JWT signing key. There is deliberately no default value
committed to the repository, so generate one (any 32+ random bytes will do):

```bash
# macOS / Linux
export JWT_SECRET_KEY=$(openssl rand -hex 32)

# Windows PowerShell
$env:JWT_SECRET_KEY = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Max 16) })
```

Then start the application:
```bash
cd backend
mvn clean install
mvn spring-boot:run          # add -Dspring-boot.run.profiles=demo for sample data
```
*(The backend will start on `http://localhost:8080`)*

The same variable is required to run the tests: `mvn test`.

### 4. Start the Frontend
In a new terminal, navigate to the `frontend` directory, install dependencies, and run the development server:
```bash
cd frontend
npm install
npm run dev
```
*(The frontend will start on `http://localhost:3000`)*

#### How the frontend reaches the backend

The browser never calls the backend or the routing engine directly. Both are
proxied through the Next.js origin by the rewrites in `frontend/next.config.mjs`:

| Browser requests | Proxied to | Configured by |
| --- | --- | --- |
| `/api/*` | `http://localhost:8080` | `BACKEND_URL` |
| `/osrm/*` | `http://localhost:5000` | `OSRM_URL` |

This keeps the client free of hard-coded hosts and ports, and means OSRM never has
to be exposed publicly with permissive CORS.

---

## 💳 Pricing and payment

The **server** is the single source of truth for price. A delivery is quoted from
the real OSRM road route (not a straight-line estimate), and the figure returned
to the browser is the figure that gets stored:

1. `POST /api/v1/client/deliveries/quote` — prices a delivery, returns
   `distanceKm`, `durationSeconds`, `price`, `exceedsTimeLimit`.
2. `POST /api/v1/client/deliveries` — creates the order as `PENDING_PAYMENT`,
   re-computing the price server-side. The 45-minute limit is enforced here.
3. `PATCH /api/v1/client/deliveries/{id}/pay` — captures payment, sets `PAID`, and
   **only then** hands the order to the auto-dispatcher.

`paymentStatus` therefore distinguishes `PENDING_PAYMENT`, `PAID`, `VOIDED` and
`REFUNDED`. Revenue is the sum of `PAID` orders only, so an unpaid or failed
checkout cannot be counted as revenue.


---

## 🔐 Authentication & Roles

Self-service registration can create a `CLIENT` or a `LIVREUR` account only. The `ADMIN` role is **never** accepted from the `/register` endpoint — the role is client-controlled, so allowing it would let anyone POST `{"role":"ADMIN"}` and authenticate as an administrator. Promotion to `ADMIN` is intentionally an out-of-band operation.

**To reach the Courier or Admin dashboards:**

The quickest route is the demo seeder described above, which creates an
administrator, two couriers and three clients in one command:

```bash
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=demo
```

| Email | Password | Role |
| :--- | :--- | :--- |
| `admin@swift.com` | `password123` | `ADMIN` |
| `courier1@swift.com` | `password123` | `LIVREUR` |
| `client1@swift.com` | `password123` | `CLIENT` |

If you would rather promote an account you registered yourself, do it directly
in the database. Registration refuses the `ADMIN` role on purpose, because the
role is chosen by the caller and accepting it would let anyone take over the
platform:

```bash
docker exec -it delivery_postgres psql -U admin -d delivery_db
```

```sql
UPDATE users SET role = 'ADMIN'   WHERE email = 'you@example.com';
UPDATE users SET role = 'LIVREUR' WHERE email = 'you@example.com';
```

Log out and back in afterwards, because the role is baked into the JWT at login.

---

## 📚 Documentation

| Document | What it covers |
| :--- | :--- |
| [`API_REFERENCE.md`](API_REFERENCE.md) | Every endpoint, with request/response shapes and the role required |
| [`SYSTEM_ARCHITECTURE.md`](SYSTEM_ARCHITECTURE.md) | Module map, the delivery/payment/dispatch flow, data model, and the AI subsystem |
| [`scripts/prepare-osrm.sh`](scripts/prepare-osrm.sh) | One-command routing data preparation |
