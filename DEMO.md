# SwiftDeliver — demo runbook

A solo demo that fits in about ten minutes. Every duration below was measured on
the local stack during a full rehearsal, not estimated.

Accounts (password `password123`): `admin@swift.com`, `courier1@swift.com`,
`courier2@swift.com`, `client1@swift.com`, `client2@swift.com`,
`client3@swift.com`.

---

## 0. Before the demo (about 5 minutes, not shown)

| Check | Command / action | Expected |
| :--- | :--- | :--- |
| Stack up | `docker compose up -d` | 5 containers; `docker ps` all healthy |
| Backend AI loaded | `curl http://localhost:8080/api/v1/health` | `"fraudModel":"loaded","fraudTrees":60` |
| OSRM up | `curl "http://localhost:5000/route/v1/driving/-7.6311,33.5891;-7.5898,33.5731?overview=false"` | `"code":"Ok"` |
| Frontend | open `http://localhost:3000/login` | login page |
| **Warm the assistant** | send one question in the client chat | first answer 20–40 s (loads the model); it is then warm for ~5 min |
| Wake the cloud | open `https://swiftdeliver-frontend.onrender.com` | free tier may answer `503` once, then `200` after ~1 min |

The assistant is a **local-only** feature (Ollama lives on the host); the cloud
deployment has no Ollama, so demo the chat locally.

---

## 1. Cloud + CI/CD (~2 min, no login)

1. GitHub → **Actions**: show the latest run green — jobs *Backend*,
   *Frontend*, *Container images*.
2. Open the deployed frontend: `https://swiftdeliver-frontend.onrender.com`.
3. Backend health (through the app's real path):
   `https://swiftdeliver-frontend.onrender.com/api/v1/health` →
   `"fraudModel":"loaded","fraudTrees":60`.

Talking point: the same commit is what CI built, what Docker runs, and what is
deployed — two web services, `autoDeployTrigger: checksPass`.

---

## 2. Baseline booking (~1.5 min) — `client1@swift.com`

1. **My deliveries** → **New delivery**.
2. Description e.g. `Demo parcel`; pick **Anfa** as pickup and **Centre** as
   drop-off on the map (or use the map click).
3. **Continue to payment** — the price comes from the server (measured 29.00 MAD
   for Anfa→Centre; quote latency **0.05 s**).
4. Confirm payment. Measured **0.05 s** to create + **4.2 s** to capture and
   auto-dispatch; the order flips to `ASSIGNED` and a courier appears on it
   (the dispatcher balances the fleet, so it may be courier2 with courier1
   already loaded).

Talking point: the order is unpaid until capture; dispatch runs only after
payment, so a failed checkout never occupies a courier.

---

## 3. Live tracking (~1.5 min) — two windows

1. **client1** — the inline live section is already on **My deliveries**; it
   reads `Connecting…` and is section-sized (measured **340 px**, section
   **436 px**), not a full-page map.
2. **courier1** (second window / incognito) — **Assigned round** → the new stop
   → **Start delivery**. The status becomes `IN_TRANSIT`, the courier badge says
   *Simulating GPS*, and the courier starts broadcasting along the OSRM route
   (optimiser **2.1 s**, OSRM proxy **0.04 s**).
3. Back on **client1** — the section turns `Live · <time>` with the courier
   marker. Measured end-to-end for this path via the UI check: **16 s**.

Note: the simulation reaches the destination and auto-marks the order
`DELIVERED`. A long route at the 1 s cadence takes ~72 s off-peak and ~215 s in
rush hour (07–09, 17–19). For repeated demos, book a **fresh** order each time so
client1 always has an active one.

---

## 4. AI fraud detection (~1.5 min) — the graded innovation

The courier's own simulation follows the real road, so it is honest by
construction. A fraudulent trajectory is produced by the scripted probe, which
drives **one honest** and **one impossible** trajectory on the same model, same
session:

```bash
cd frontend
node verify_fraud_panel.js     # measured 65.6 s (50 s of it the real 10 s cadence)
```

Then, as `admin@swift.com` → **Fleet overview**:

* the `PANEL-FRAUD` row carries an **AI alert** tag, `PANEL-HONEST` an **AI ok**
  tag, and a never-driven order carries **no tag at all** (three distinct
  states, not two);
* click the tag → **Trajectory risk** modal: the per-fix score curve, the
  threshold the decisions were taken against, "not proof of wrongdoing", and
  "Training data is synthetic".

Same check standalone: `node verify_fraud_live.js` (runs ~6 min — a ~4 min honest
pass at the real cadence plus a ~2 min stall-then-vanish pass; it proves the two
verdicts disagree on one delivery).

Talking point: offline Python Random Forest → gzipped JSON → live Java scoring;
60 trees, threshold 0.82; Isolation Forest was built, measured and **rejected**.
At 2 % false-positive rate RF scores 0.78/0.896 vs 0.585/0.358 for the speed
rule.

---

## 5. Security (~1 min)

```bash
cd frontend
node verify_auth_hardening.js   # measured 1.2 s, 11/11
node verify_proof.js            # measured 1.5 s, 23/23
```

* sign-out bumps `tokenVersion`; the **same** token is then refused (403), a
  fresh sign-in works;
* the 6th wrong password in 15 min → **429** with `Retry-After: 900`;
* a delivery proof is an HMAC over the sealed fields and re-verifies via
  `GET /api/v1/admin/deliveries/{id}/proof`.

**Ordering warning:** `verify_auth_hardening.js` signs out `admin@swift.com`,
which revokes any admin browser session. Run it **before** you open the admin
dashboard, or simply sign in again afterwards. It uses a throwaway account for
the rate-limit test, so no demo account is ever locked.

---

## 6. Assistant (~1 min) — `client1@swift.com`

Ask, in the chat widget:

* *How is the delivery price calculated?* → 15 MAD base + 5 MAD/km
* *Can I pay cash on delivery?* → no
* *What is the refund policy if my delivery is late?* → 10 % over 10 min, full
  refund over 45 min

Measured: first (cold) answer **21.6 s**, subsequent **3.4–5.8 s**. Answers are
retrieved from `faq.txt` via pgvector and generated by `llama3.1:8b` on the
RTX 4060 (100 % GPU). Zero cost.

---

## Run-of-show timing

| # | Segment | Measured |
| :- | :--- | :--- |
| 1 | Cloud + CI | ~2 min (browsing) |
| 2 | Booking + auto-dispatch | ~1.5 min (quote 0.05 s, pay 4.2 s) |
| 3 | Live tracking | ~1.5 min (UI path 16 s) |
| 4 | AI fraud panel | ~1.5 min (script 65.6 s) |
| 5 | Security | ~1 min (checks 2.7 s combined) |
| 6 | Assistant | ~1 min (cold 21.6 s, warm ~4 s) |
| | **Total** | **~8.5 min** + talking |

---

## Friction found in rehearsal, and the fix

1. **The security script logs out admin** → any open admin tab is signed out.
   Run it before opening the admin dashboard, or sign back in.
2. **The courier simulation auto-completes at the destination** → a seeded
   `IN_TRANSIT` order is consumed. Book a fresh order per demo instead of
   relying on the seed.
3. **The live courier sim cannot produce fraud** (it follows real roads). Fraud
   is shown with the scripted probe, which takes ~66 s — talk over it, or
   pre-run it and just present the resulting panel.
4. **Assistant cold start** is 20–40 s for the first question. Warm it in step 0.
5. **Cloud free tier** sleeps after ~15 min idle and its Postgres is deleted
   after 30 days without backup. Wake it before the demo; recreate/re-apply
   within 30 days.
