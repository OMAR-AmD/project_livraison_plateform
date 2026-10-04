# SwiftDeliver — demo runbook

A solo demo that fits in about ten minutes. Every duration below was measured on
the local stack during a full rehearsal, not estimated. The ordering is the one
validated end-to-end by `frontend/rehearse_demo.js` (see "Full rehearsal" below).

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

Optional but recommended: run the whole sequence once as a rehearsal. It plays
the real UI and the real scripts in order and timestamps every phase:

```bash
cd frontend
node rehearse_demo.js
```

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

## 2. Security (~1 min) — run this FIRST

Security comes before any dashboard is opened, deliberately:
`verify_auth_hardening.js` signs out `admin@swift.com`, which revokes any admin
browser session. If it ran last, the admin tab would be dead for the rest of the
demo. Run first, it is invisible.

```bash
cd frontend
node verify_auth_hardening.js   # measured 1.1 s, 11/11
node verify_proof.js            # measured 1.5 s, 23/23
```

* sign-out bumps `tokenVersion`; the **same** token is then refused (403), a
  fresh sign-in works;
* the 6th wrong password in 15 min → **429** with `Retry-After: 900`;
* a delivery proof is an HMAC over the sealed fields and re-verifies via
  `GET /api/v1/admin/deliveries/{id}/proof`.

It uses a throwaway account for the rate-limit test, so no demo account is ever
locked.

---

## 3. Baseline booking (~1.5 min) — `client1@swift.com`

1. **My deliveries** → **New delivery**.
2. Description e.g. `Demo parcel`; set pickup and drop-off by **clicking the
   map**. Click the map, wait for the address box to fill, repeat for the second
   point. On a phone you can press **📍 Use my GPS location** instead: it drops
   the pin and fills the box with the reverse-geocoded address. Once the pickup
   is set it stays visible on the **drop-off** map as a blue **Pickup** pin, so
   you can place the second point relative to it (the map frames both pins).
3. **Continue to payment** — the price comes from the server (measured 0.3–0.4 s;
   ~29–32 MAD for two Casablanca points).
4. Confirm payment. Measured **4.6 s** to create + capture; the order flips to
   `ASSIGNED` and a courier appears on it (the dispatcher balances the fleet, so
   it may be courier2 with courier1 already loaded).

**Do not type "Centre, Casablanca".** Nominatim resolves it to the Centre region
of **Cameroon**; the road route then exceeds the 45-minute limit and checkout
refuses to open. If you prefer to type, use full unambiguous queries
(`Anfa, Casablanca, Morocco`) and check the pin lands inside the city.

Talking point: the order is unpaid until capture; dispatch runs only after
payment, so a failed checkout never occupies a courier.

---

## 4. Live tracking (~1.5 min) — two windows

1. **client1** — the inline live section is already on **My deliveries**; it
   reads `Connecting…` and is section-sized (measured **340 px**, section
   **436 px**), not a full-page map.
2. **courier1** (second window / incognito) — **Assigned round** → the new stop
   → **Start delivery**. The status becomes `IN_TRANSIT`, the courier badge says
   *Simulating GPS*, and the courier starts broadcasting along the OSRM route
   (optimiser **2.1 s**, OSRM proxy **0.04 s**).
3. Back on **client1** — the section turns `Live · <time>` with the courier
   marker. Measured **4.7 s** from *Start delivery* to `Live` in the rehearsal.

Note: the simulation reaches the destination and auto-marks the order
`DELIVERED`. A long route at the 1 s cadence takes ~72 s off-peak and ~215 s in
rush hour (07–09, 17–19). For repeated demos, book a **fresh** order each time so
client1 always has an active one.

### Handover code — confirming at the door (~1 min)

Where GPS cannot place the courier within 500 m (courtyard, medina alley,
dead battery zone), the recipient shows a code instead:

1. **client1** — on the active order, **Code**: a QR plus six big digits.
   The code is HMAC-derived per order, issued only to the owning client, and
   single-use (refused once the order closes).
2. **courier1** — on the stop, **Scan code**: point the camera at the QR
   (`BarcodeDetector`, no dependency) or type the six digits — the fallback
   that also makes the desktop demo work.
3. A wrong code is refused with `Invalid handover code` and changes nothing;
   the right one seals the delivery with `codeVerified`, even with no usable
   position (sealed coordinates fall back to the destination, with no distance
   claim — the proof never invents a GPS fix).

Talking point: the code proves code-presence, GPS proves place — the sealed
proof records which factors it had. A forwarded photo of the code would defeat
it, which is stated on screen rather than hidden. Verify with
`node verify_handover.js` (10 checks).

### Optional: the courier's real phone GPS

The mode is picked automatically: the laptop and the rehearsal stay on the
simulator, so their behaviour is deterministic. To show a **real** trajectory:

1. Open the **deployed site (HTTPS)** on the phone, sign in as **courier1**, start
   the delivery.
2. The badge reads **Sharing live location** (not *Simulating GPS*), and the
   client section follows the phone as you move. Zoom to street level (up to
   zoom 19) to see the marker track small movements.
3. Switch either way at any time with the header button **Use my real GPS** /
   **Simulate route**.
4. While sharing, the page holds a **screen wake lock** so the phone does not
   sleep: a sleeping phone suspends geolocation, which used to make the client
   read **Signal lost** mid-delivery. Locking the screen by hand still does.

Geolocation is only allowed in a **secure context**, so this works on the
deployed site (`https://…onrender.com`) or `localhost`, but **not** on the LAN URL
`http://192.168.137.1:3000` — there the button warns and stays on the simulator.
On the cloud the client's map also needs the cache in the backend's region
(Render Blueprint re-apply); the courier's own screen follows the phone
regardless. Verify end-to-end with `node verify_real_gps.js`.

---

## 5. AI fraud detection (~1.5 min) — the graded innovation

The courier's own simulation follows the real road, so it is honest by
construction. A fraudulent trajectory is produced by the scripted probe, which
drives **one honest** and **one impossible** trajectory on the same model, same
session. Run it with `KEEP=1` so the three probe rows are still there when you
open the fleet table — by default the script deletes them, and then there is
nothing to show:

```bash
cd frontend
KEEP=1 node verify_fraud_panel.js     # measured 65.8 s (50 s of it the real 10 s cadence)
```

PowerShell: `$env:KEEP=1; node verify_fraud_panel.js`.

Then, as `admin@swift.com` → **Fleet overview**:

* the `PANEL-FRAUD` row carries an **AI alert** tag, `PANEL-HONEST` an **AI ok**
  tag, and a never-driven order carries **no tag at all** (three distinct
  states, not two);
* click the tag → **Trajectory risk** modal: the per-fix score curve, the
  threshold the decisions were taken against, "not proof of wrongdoing", and
  "Training data is synthetic".

Clean up after the demo with the **Delete** button on the three `PANEL-*` rows.
A run without `KEEP=1` cleans up after itself.

Same check standalone: `node verify_fraud_live.js` (runs ~6 min — a ~4 min honest
pass at the real cadence plus a ~2 min stall-then-vanish pass; it proves the two
verdicts disagree on one delivery).

Talking point: offline Python Random Forest → gzipped JSON → live Java scoring;
60 trees, threshold 0.82; Isolation Forest was built, measured and **rejected**.
At 2 % false-positive rate RF scores 0.78/0.896 vs 0.585/0.358 for the speed
rule.

---

## 6. Assistant (~1 min) — `client1@swift.com`

Ask, in the chat widget:

* *How is the delivery price calculated?* → 15 MAD base + 5 MAD/km
* *Can I pay cash on delivery?* → no
* *What is the refund policy if my delivery is late?* → 10 % over 10 min, full
  refund over 45 min

Measured: first (cold) answer **21.6 s**, warm **7.1 s** in the timed rehearsal.
Answers are retrieved from `faq.txt` via pgvector and generated by `llama3.1:8b`
on the RTX 4060 (100 % GPU). Zero cost.

---

## Run-of-show timing

| # | Segment | Mechanical | On stage |
| :- | :--- | :--- | :--- |
| 1 | Cloud + CI | — | ~2 min (browsing) |
| 2 | Security (first) | 2.6 s (two scripts) | ~1 min |
| 3 | Booking + auto-dispatch | 10.6 s (quote 0.3 s, pay 4.6 s) | ~1.5 min |
| 4 | Live tracking | 5.7 s (Live 4.7 s after Start) | ~1.5 min |
| 5 | AI fraud panel | 78.8 s (script 65.8 s + panel) | ~1.5 min |
| 6 | Assistant | 7.2 s (warm) | ~1 min |
| | **Total** | **~105 s of actions** | **~8.5 min + talking** |

The difference between the two columns is the presenter talking and browsing;
the actions themselves were measured by `node rehearse_demo.js`.

---

## Full rehearsal

`frontend/rehearse_demo.js` plays the corrected order against the running stack:
it runs `verify_auth_hardening.js` and `verify_proof.js`, books a fresh order by
driving the real booking UI, starts the courier simulation, watches the client
map go Live, runs `verify_fraud_panel.js` with `KEEP=1`, checks the admin panel
tags and the score-curve modal, then asks the assistant a question. It cleans up
its own orders at the end and prints a per-phase timeline. Latest run: **14/14
checks, 105.6 s total**.

---

## Friction found in rehearsal, and the fix

1. **Security last killed the admin tab.** `verify_auth_hardening.js` signs out
   admin. It is now segment 2, before any dashboard is opened.
2. **The fraud script deleted its own evidence.** `verify_fraud_panel.js` cleaned
   up its rows in `finally`, so opening **Fleet overview** after it showed
   nothing. It now accepts `KEEP=1` to leave the three probe rows in place.
3. **`Centre, Casablanca` is in Cameroon.** The free-text geocoder matched the
   Centre region; the route exceeded the 45-minute limit and checkout never
   opened. Book by clicking the map, or use full unambiguous addresses.
4. **A long reverse-geocoded address broke the insert.** Nominatim returns the
   full hierarchy in French + Arabic + Tifinagh, past the 255-character column,
   and the server surfaced it as a misleading **409 "a record already exists"**.
   Fixed: `LocationPicker` keeps the first few components and caps the label at
   200 characters, and `DeliveryRequest` bounds the address at 255 so any other
   client gets a clear 400.
5. **The courier simulation auto-completes at the destination** → a seeded
   `IN_TRANSIT` order is consumed. Book a fresh order per demo.
6. **The live courier sim cannot produce fraud** (it follows real roads). Fraud
   is shown with the scripted probe, which takes ~66 s — talk over it, or
   pre-run it with `KEEP=1` and present the resulting panel.
7. **Assistant cold start** is 20–40 s for the first question. Warm it in step 0.
8. **Cloud free tier** sleeps after ~15 min idle and its Postgres is deleted
   after 30 days without backup. Wake it before the demo; recreate/re-apply
   within 30 days.
