/**
 * Does the courier's phone really broadcast its own position?
 *
 * WHAT THIS CHECKS
 * The app now decides the location mode from the environment. This exercises it
 * end-to-end through the running UI:
 *   - a laptop must default to the simulated route ("Simulating GPS");
 *   - a phone on a secure origin, with geolocation granted, must default to real
 *     GPS ("Sharing live location") and actually PATCH the coordinates the
 *     browser reports to /courier/deliveries/{id}/location;
 *   - the courier can override the choice with the header button, and the
 *     override sticks (the auto-selector must not fight the user);
 *   - while broadcasting real GPS the app must request a screen wake lock, or a
 *     phone that sleeps silences the stream and the client reads "Signal lost".
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * The two device profiles must land on OPPOSITE modes, and the phone case also
 * asserts the payload really reached the server, not just that a label changed.
 * It sets up its own IN_TRANSIT orders, so it does not depend on the demo seed.
 *
 * MUTATION
 * Flip `return !canUseRealGps;` to `return true;` in src/lib/gpsMode.js: the
 * phone check fails ("Simulating GPS"). Flip it to `return false;`: the laptop
 * check fails ("Sharing live location"). Both were verified to fail.
 * Delete the wake-lock effect in CourierView.js: the "requests a screen wake
 * lock" check fails.
 *
 * RUN  (the backend must be up; WEB points at a frontend built from this tree)
 * node verify_real_gps.js
 * WEB=http://localhost:3001 node verify_real_gps.js   # against `next dev`
 */

const { chromium, devices } = require('playwright');

const BASE = process.env.WEB || 'http://localhost:3000';
const API = 'http://localhost:8080/api/v1';
const PASSWORD = 'password123';

const PICKUP = { lat: 33.5891, lng: -7.6311 }; // Anfa
const DROPOFF = { lat: 33.5731, lng: -7.5898 }; // Centre
const STUB = { latitude: 33.588, longitude: -7.633 }; // what the "phone" reports

let passed = 0;
const failures = [];

function check(name, condition, detail = '') {
  if (condition) {
    passed++;
    console.log(`OK   ${name}${detail ? '  (' + detail + ')' : ''}`);
  } else {
    failures.push(name + (detail ? '  (' + detail + ')' : ''));
    console.log(`KO   ${name}${detail ? '  (' + detail + ')' : ''}`);
  }
}

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

async function api(path, headers, options = {}) {
  const r = await fetch(`${API}${path}`, {
    ...options,
    headers: { ...headers, ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  const body = r.status === 204 ? null : await r.json().catch(() => null);
  return { status: r.status, ok: r.ok, body };
}

async function createDelivery(client, label) {
  const r = await api('/client/deliveries', client, {
    method: 'POST',
    body: JSON.stringify({
      description: `gps probe ${label}`,
      pickupAddress: 'Anfa, Casablanca',
      pickupLat: PICKUP.lat,
      pickupLng: PICKUP.lng,
      dropoffAddress: 'Centre, Casablanca',
      dropoffLat: DROPOFF.lat,
      dropoffLng: DROPOFF.lng,
    }),
  });
  if (!r.ok) throw new Error(`could not create probe delivery: ${r.status}`);
  return r.body.id;
}

async function signIn(page, email) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.waitForSelector('#login-email', { timeout: 120000 });
  await page.waitForFunction(() => {
    const form = document.querySelector('form');
    if (!form) return false;
    const key = Object.keys(form).find((k) => k.startsWith('__reactProps$'));
    return !!key && typeof form[key].onSubmit === 'function';
  }, { timeout: 120000 });
  await page.fill('#login-email', email);
  await page.fill('#login-password', PASSWORD);
  await page.click('button[type=submit]');
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 60000 });
}

async function startProbe(client, admin, courier1, c1, label) {
  const id = await createDelivery(client, label);
  await api(`/admin/deliveries/${id}/assign`, admin, {
    method: 'PATCH',
    body: JSON.stringify({ courierId: c1.id }),
  });
  const started = await api(`/courier/deliveries/${id}/status`, courier1, {
    method: 'PATCH',
    body: JSON.stringify({ status: 'IN_TRANSIT' }),
  });
  check(`probe "${label}" reached IN_TRANSIT`, started.ok, `status ${started.status}`);
  return id;
}

async function main() {
  const admin = await auth('admin@swift.com');
  const courier1 = await auth('courier1@swift.com');
  const client3 = await auth('client3@swift.com');

  const couriers = (await api('/admin/users/couriers', admin)).body;
  const list = couriers.content || couriers;
  const c1 = list.find((u) => u.email === 'courier1@swift.com');

  const made = [];
  const browser = await chromium.launch();

  try {
    // ---- laptop: must stay on the deterministic simulated route ----
    console.log('\n-- laptop profile --');
    const deskId = await startProbe(client3, admin, courier1, c1, 'desktop');
    made.push(deskId);
    const deskCtx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const deskPage = await deskCtx.newPage();
    await signIn(deskPage, 'courier1@swift.com');
    await deskPage.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const deskBadge = deskPage.getByText('Simulating GPS');
    await deskBadge.waitFor({ state: 'visible', timeout: 45000 }).catch(() => {});
    check('the laptop defaults to the simulated route', await deskBadge.isVisible().catch(() => false));
    await deskCtx.close();

    // ---- phone on a secure origin: real GPS by default ----
    console.log('\n-- phone profile (geolocation granted) --');
    const phoneId = await startProbe(client3, admin, courier1, c1, 'phone');
    made.push(phoneId);
    const phoneCtx = await browser.newContext({
      ...devices['Pixel 5'],
      permissions: ['geolocation'],
      geolocation: STUB,
    });

    // Count wake-lock requests without depending on the headless browser
    // exposing the API: stub it in when it is missing.
    await phoneCtx.addInitScript(() => {
      window.__wakeLockRequests = 0;
      if (!navigator.wakeLock) {
        Object.defineProperty(navigator, 'wakeLock', {
          configurable: true,
          value: {
            request: async () => ({ release: async () => {}, addEventListener: () => {} }),
          },
        });
      }
      const request = navigator.wakeLock.request.bind(navigator.wakeLock);
      navigator.wakeLock.request = (...args) => {
        window.__wakeLockRequests += 1;
        return request(...args);
      };
    });

    const phonePage = await phoneCtx.newPage();

    let broadcast = null;
    phonePage.on('request', (req) => {
      if (req.method() === 'PATCH' && req.url().includes(`/courier/deliveries/${phoneId}/location`)) {
        try { broadcast = JSON.parse(req.postData() || '{}'); } catch { /* keep last good */ }
      }
    });

    await signIn(phonePage, 'courier1@swift.com');
    await phonePage.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const liveBadge = phonePage.getByText('Sharing live location');
    await liveBadge.waitFor({ state: 'visible', timeout: 60000 }).catch(() => {});
    check('the phone defaults to real GPS', await liveBadge.isVisible().catch(() => false));

    // The wake lock is what keeps the phone screen on so the stream survives.
    let wakeLocks = 0;
    const wakeDeadline = Date.now() + 15000;
    while (Date.now() < wakeDeadline) {
      wakeLocks = await phonePage.evaluate(() => window.__wakeLockRequests || 0);
      if (wakeLocks >= 1) break;
      await phonePage.waitForTimeout(500);
    }
    check('sharing real GPS requests a screen wake lock', wakeLocks >= 1, `${wakeLocks} request(s)`);

    const closes = () =>
      broadcast &&
      Math.abs(broadcast.latitude - STUB.latitude) < 1e-4 &&
      Math.abs(broadcast.longitude - STUB.longitude) < 1e-4;
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline && !closes()) await phonePage.waitForTimeout(1000);
    check('the phone broadcasts its real position to the server', closes(),
      broadcast ? JSON.stringify(broadcast) : 'no location PATCH seen');

    // ---- manual override sticks ----
    await phonePage.getByRole('button', { name: 'Simulate route' }).click();
    const simBadge = phonePage.getByText('Simulating GPS');
    await simBadge.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    check('the courier can switch the phone back to the simulator', await simBadge.isVisible().catch(() => false));

    await phonePage.getByRole('button', { name: 'Use my real GPS' }).click();
    await liveBadge.waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    check('the override back to real GPS sticks', await liveBadge.isVisible().catch(() => false));

    await phoneCtx.close();
  } catch (e) {
    check('threw: ' + String(e).split('\n')[0].slice(0, 160), false);
  } finally {
    console.log('\n-- cleanup --');
    for (const id of made) {
      const r = await api(`/admin/deliveries/${id}`, admin, { method: 'DELETE' });
      console.log(`   ${id.slice(0, 8)} deleted: ${r.ok} (${r.status})`);
    }
    await browser.close();
  }

  console.log('\n' + '='.repeat(72));
  if (failures.length === 0) {
    console.log(`ALL REAL GPS CHECKS OK (${passed})`);
  } else {
    console.log(`${failures.length} FAILURE(S) OUT OF ${passed + failures.length}:`);
    failures.forEach((f) => console.log('  - ' + f));
  }
  process.exit(failures.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('\nscript failed:', e.message);
  process.exit(1);
});
