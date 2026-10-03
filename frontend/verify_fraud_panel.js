/**
 * Is the fraud detection actually visible in the product?
 *
 * WHAT THIS CHECKS
 * verify_fraud_live.js proves the model fires on real position updates. This
 * proves a human can see it: the admin dashboard tags a delivery the model
 * flagged, tags a delivery it cleared, shows the score curve behind the tag,
 * and refuses to claim anything about a delivery it never scored.
 *
 * WHY THE THREE STATES ARE SEPARATE
 * The interesting assertion here is not "a badge appears". It is that the panel
 * distinguishes three situations a naive implementation collapses into one:
 *
 *   flagged        the model fired
 *   cleared        the model looked and stayed quiet
 *   never scored   the model never saw this delivery
 *
 * A panel that only ever renders the first two looks complete and is lying
 * about the third: an unassigned order sitting in PENDING would either get a
 * reassuring green tick for a measurement nobody made, or get silently lumped
 * in with the alerts. So all three are set up and all three are asserted, and
 * the third is only meaningful because the first two are proved first.
 *
 * WHY IT DRIVES REAL TRAJECTORIES
 * The badges are not seeded into Redis. Two deliveries are driven through the
 * courier API at the real 10 s cadence — one honestly, one with a physically
 * impossible jump — and the panel is read afterwards. Nothing about the
 * expected outcome is injected, so a broken wiring between DeliveryService,
 * FraudTrailStore and the endpoint fails here rather than passing.
 *
 * RUN
 * node verify_fraud_panel.js
 */

const { chromium } = require('playwright');

const WEB = 'http://localhost:3000';
const API = 'http://localhost:8080/api/v1';
const PASSWORD = 'password123';

const FIX_INTERVAL_MS = 10000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The probe deliveries are deleted by default: a verification run must not
// litter the database. The demo needs the opposite — after the script exits,
// the dispatcher has to be able to open the fleet table and SEE the two tags.
// `KEEP=1` opts out of the cleanup and prints the ids so they can be removed
// afterwards. The default is unchanged, so an ordinary run still cleans up.
const KEEP = /^(1|true|yes)$/i.test(process.env.KEEP || '');

const results = [];
const check = (name, pass, detail = '') => {
  results.push([pass, name, detail]);
  console.log(`  ${pass ? 'OK  ' : 'KO  '} ${name}${detail ? `  (${detail})` : ''}`);
};

async function auth(email) {
  const r = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: PASSWORD }),
  });
  if (!r.ok) throw new Error(`login failed for ${email}: ${r.status}`);
  const { token } = await r.json();
  return { Authorization: `Bearer ${token}` };
}

const north = (m) => m / 111320;
const east = (lat, m) => m / (111320 * Math.cos((lat * Math.PI) / 180));

const TARGET_LAT = 33.5891;
const TARGET_LNG = -7.6311;

async function createDelivery(client, description) {
  const r = await fetch(`${API}/client/deliveries`, {
    method: 'POST',
    headers: { ...client, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description,
      pickupAddress: 'panel probe pickup',
      pickupLat: TARGET_LAT,
      pickupLng: TARGET_LNG,
      dropoffAddress: 'panel probe dropoff',
      dropoffLat: TARGET_LAT,
      dropoffLng: TARGET_LNG,
    }),
  });
  if (!r.ok) throw new Error(`could not create "${description}": ${r.status} ${await r.text()}`);
  return (await r.json()).id;
}

async function startDelivery(courier, id) {
  const assign = await fetch(`${API}/admin/deliveries/${id}/assign`, {
    method: 'PATCH',
    headers: { ...courier.admin, 'Content-Type': 'application/json' },
    body: JSON.stringify({ courierId: courier.id }),
  });
  if (!assign.ok) throw new Error(`assign failed: ${assign.status}`);

  const start = await fetch(`${API}/courier/deliveries/${id}/status`, {
    method: 'PATCH',
    headers: { ...courier.headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'IN_TRANSIT' }),
  });
  if (!start.ok) throw new Error(`could not set IN_TRANSIT: ${start.status}`);
}

async function broadcast(headers, id, lat, lng) {
  const r = await fetch(`${API}/courier/deliveries/${id}/location`, {
    method: 'PATCH',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ latitude: lat, longitude: lng }),
  });
  return r.status;
}

/**
 * Drive both deliveries at once so the honest pass and the fraudulent pass
 * share the same wall clock. Running them in sequence would double the wait for
 * no extra evidence: what is being compared is the model's verdict on two
 * trajectories, not their order.
 */
async function driveScenarios(courier, honestId, fraudId, fixCount) {
  for (let i = 0; i < fixCount; i++) {
    // Closing on the drop-off at ~3 m/s, which is a normal urban courier pace.
    const remaining = 600 - i * (600 / fixCount);
    await broadcast(courier.headers, honestId, TARGET_LAT - north(remaining), TARGET_LNG);

    // The courier drifts a few metres, then reports arrival from 1.5 km away.
    // Same courier, same target, same cadence: only the trajectory differs.
    if (i < fixCount - 2) {
      await broadcast(courier.headers, fraudId, TARGET_LAT - north(400) + north(i * 3), TARGET_LNG);
    } else {
      await broadcast(courier.headers, fraudId,
        TARGET_LAT + north(1500), TARGET_LNG + east(TARGET_LAT, 900));
    }
    await sleep(FIX_INTERVAL_MS);
  }
}

async function main() {
  console.log('='.repeat(72));
  console.log('Fraud panel check (real trajectories, real dashboard)');
  console.log('='.repeat(72));

  const client = await auth('client1@swift.com');
  const courierHeaders = await auth('courier1@swift.com');
  const adminHeaders = await auth('admin@swift.com');
  const couriers = await (await fetch(`${API}/admin/users/couriers`, { headers: adminHeaders })).json();
  const courier1 = (couriers.content || couriers).find((c) => c.email === 'courier1@swift.com');
  if (!courier1) throw new Error('courier1 not found');

  const courier = { headers: courierHeaders, id: courier1.id, admin: adminHeaders };

  const honestId = await createDelivery(client, 'PANEL-HONEST');
  const fraudId = await createDelivery(client, 'PANEL-FRAUD');
  const untouchedId = await createDelivery(client, 'PANEL-NEVER-SCORED');
  console.log(`  honest=${honestId.slice(0, 8)}  fraud=${fraudId.slice(0, 8)}  never-driven=${untouchedId.slice(0, 8)}`);

  try {
    await startDelivery(courier, honestId);
    await startDelivery(courier, fraudId);
    // untouchedId is deliberately left PENDING and unassigned. It is the third
    // state: a delivery the model has no opinion about.

    console.log(`\n-- driving ${5} fixes each at a real ${FIX_INTERVAL_MS / 1000}s cadence --`);
    await driveScenarios(courier, honestId, fraudId, 5);

    // Confirm the premise before reading the UI. If neither scenario produced
    // the verdict this check expects, a missing badge is a test problem and not
    // a dashboard problem, and it has to be reported as such rather than
    // folded into a UI failure.
    const trailOf = async (id) => {
      const r = await fetch(`${API}/admin/deliveries/${id}/fraud-trail`, { headers: adminHeaders });
      if (r.status === 409) return null;
      return (await r.json()).points;
    };
    const honestPoints = (await trailOf(honestId)) ?? [];
    const fraudPoints = (await trailOf(fraudId)) ?? [];
    const honestFlagged = honestPoints.filter((p) => p.fraud).length;
    const fraudFlagged = fraudPoints.filter((p) => p.fraud).length;
    console.log(`   honest: ${honestFlagged}/${honestPoints.length} flagged; fraud: ${fraudFlagged}/${fraudPoints.length} flagged`);
    check('the scenario produced one cleared trajectory and one flagged one',
      honestPoints.length > 0 && fraudPoints.length > 0 && honestFlagged === 0 && fraudFlagged > 0,
      `honest ${honestFlagged}/${honestPoints.length}, fraud ${fraudFlagged}/${fraudPoints.length}`);
    if (honestPoints.length === 0 || fraudPoints.length === 0) {
      console.log('\n  premise failed: no trail was recorded, so the dashboard has nothing to show.');
      return;
    }

    // ===================== the dashboard =====================================
    const browser = await chromium.launch();
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await ctx.newPage();
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 120)));

    await page.goto(`${WEB}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    await page.goto(`${WEB}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.waitForSelector('#login-email', { timeout: 120000 });
    // Wait for hydration, not just for the button: the server-rendered HTML
    // already carries an enabled submit, and clicking it before React attaches
    // the handler does nothing at all.
    await page.waitForFunction(() => {
      const form = document.querySelector('form');
      if (!form) return false;
      const key = Object.keys(form).find((k) => k.startsWith('__reactProps$'));
      return !!key && typeof form[key].onSubmit === 'function';
    }, { timeout: 120000 });
    await page.fill('#login-email', 'admin@swift.com');
    await page.fill('#login-password', PASSWORD);
    await page.click('button[type=submit]');
    await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 60000 });
    check('admin signed in', true);

    await page.goto(`${WEB}/dashboard/deliveries`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.waitForTimeout(9000); // first paint + the trail fetch

    // --- the monitor must be reachable, or absence means nothing -------------
    const banner = await page.locator('[role=alert]').filter({ hasText: /Trajectory monitoring is unavailable/i }).count();
    check('the panel reports the monitor as reachable', banner === 0,
      banner > 0 ? 'an error banner is showing' : 'no outage banner');

    // --- state 1: flagged ---------------------------------------------------
    const flaggedRow = page.locator('tr', { hasText: 'PANEL-FRAUD' }).first();
    const flaggedTag = flaggedRow.locator('button', { hasText: /AI alert/ });
    check('the flagged delivery is badged', (await flaggedTag.count()) > 0,
      `${await flaggedTag.count()} tag(s) on the PANEL-FRAUD row`);

    // --- state 2: cleared ---------------------------------------------------
    const honestRow = page.locator('tr', { hasText: 'PANEL-HONEST' }).first();
    const honestTag = honestRow.locator('button', { hasText: /AI ok/ });
    const honestAlertTag = honestRow.locator('button', { hasText: /AI alert/ });
    check('the cleared delivery is badged as looked-at, not as never-seen',
      (await honestTag.count()) > 0, `${await honestTag.count()} tag(s)`);
    check('the cleared delivery is not badged as an alert',
      (await honestAlertTag.count()) === 0,
      `${await honestAlertTag.count()} alert tag(s) — the model did flag it`);

    // --- state 3: never scored ---------------------------------------------
    const untouchedRow = page.locator('tr', { hasText: 'PANEL-NEVER-SCORED' }).first();
    const untouchedAny = await untouchedRow.locator('button', { hasText: /^AI / }).count();
    check('a delivery the model never scored carries no tag at all',
      untouchedAny === 0, `${untouchedAny} tag(s); a tag here would claim a measurement nobody made`);

    // --- the curve behind the badge ----------------------------------------
    await flaggedTag.first().click();
    const modal = page.locator('[role=dialog]').filter({ hasText: /Trajectory risk/i });
    await modal.waitFor({ state: 'visible', timeout: 30000 });
    await page.waitForTimeout(2500); // the per-fix fetch
    const polyline = modal.locator('polyline').first();
    const pointsInPolyline = await polyline.getAttribute('points');
    const pointCount = (pointsInPolyline || '').trim().split(/\s+/).filter(Boolean).length;
    check('the modal draws the per-fix score curve',
      pointCount >= 5, `${pointCount} points plotted (expected at least 5)`);
    check('the modal states the threshold the decisions were taken against',
      (await modal.getByText(/threshold\s+\d+%/i).count()) > 0);
    check('the modal says the score is not proof of wrongdoing',
      (await modal.getByText(/not proof of wrongdoing/i).count()) > 0,
      'the panel must not let an operator read a score as a verdict');
    check('the modal discloses that the training data is synthetic',
      (await modal.getByText(/Training data is synthetic/i).count()) > 0);

    await page.keyboard.press('Escape');
    await page.waitForTimeout(1200);

    check('no uncaught page error while the panel rendered', pageErrors.length === 0,
      pageErrors.join(' | ') || 'none');

    await browser.close();

    // --- the panel must not be a client-side view of secrets ---------------
    const clientCanRead = await fetch(`${API}/admin/deliveries/fraud-trails`, { headers: client });
    check('a client cannot read the fleet-wide monitor', clientCanRead.status === 403,
      `HTTP ${clientCanRead.status}`);
    const courierCanRead = await fetch(`${API}/admin/deliveries/fraud-trails`, { headers: courierHeaders });
    check('a courier cannot read the fleet-wide monitor', courierCanRead.status === 403,
      `HTTP ${courierCanRead.status}`);
  } finally {
    if (KEEP) {
      console.log('  KEEP=1: probes left in place for the live panel');
      console.log(`  KEEP_IDS=${[honestId, fraudId, untouchedId].join(',')}`);
    } else {
      for (const id of [honestId, fraudId, untouchedId]) {
        const r = await fetch(`${API}/admin/deliveries/${id}`, { method: 'DELETE', headers: adminHeaders });
        console.log(`  cleanup ${id.slice(0, 8)}: HTTP ${r.status}`);
      }
    }
  }

  const ko = results.filter(([pass]) => !pass).length;
  console.log('\n' + '='.repeat(72));
  if (ko === 0) {
    console.log(`ALL CHECKS OK (${results.length})`);
  } else {
    console.log(`${ko} FAILURE(S) of ${results.length}:`);
    results.filter(([pass]) => !pass).forEach(([, n, d]) => console.log(`  - ${n} (${d})`));
  }
  process.exit(ko === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('\nscript failed:', e.message);
  process.exit(1);
});