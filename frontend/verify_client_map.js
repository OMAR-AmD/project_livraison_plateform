/**
 * Is the client's always-visible order map real on the running platform?
 *
 * WHAT THIS CHECKS
 * The client used to reach a live map only through a "Track" button, and only
 * while the order was IN_TRANSIT. This proves the new always-visible section:
 * that it renders above the order list for an active order, that it is sized as
 * a section (a bounded height, not the admin's full-height fleet map), that it
 * turns "Live" once the courier broadcasts, and that it does NOT appear for a
 * client whose order is still PENDING.
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * It asserts both directions: the in-transit client MUST show the map and the
 * pending client MUST NOT. A component rendered unconditionally fails the
 * second; a component that never renders fails the first. The height assertion
 * fails if the map silently reverts to OptimizedRouteMap's 500px default or to
 * a full-page size, which is exactly the UI regression this feature was asked
 * to avoid.
 *
 * WHY IT SETS UP ITS OWN ORDERS
 * The demo seed only guarantees an IN_TRANSIT order until the first script that
 * completes it. This script creates an in-transit order, an assigned order and
 * a pending order, so it is independent of whatever earlier runs left behind.
 *
 * RUN
 * node verify_client_map.js
 */

const { chromium } = require('playwright');

const BASE = 'http://localhost:3000';
const API = 'http://localhost:8080/api/v1';
const PASSWORD = 'password123';

const PICKUP = { lat: 33.5891, lng: -7.6311 }; // Anfa
const DROPOFF = { lat: 33.5731, lng: -7.5898 }; // Centre
const BROADCAST = { latitude: 33.582, longitude: -7.6 }; // Roches-Noires, between the two

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
      description: `map probe ${label}`,
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

async function assign(admin, deliveryId, courierId) {
  return api(`/admin/deliveries/${deliveryId}/assign`, admin, {
    method: 'PATCH',
    body: JSON.stringify({ courierId }),
  });
}

async function setStatus(courier, deliveryId, status) {
  return api(`/courier/deliveries/${deliveryId}/status`, courier, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  });
}

async function main() {
  const admin = await auth('admin@swift.com');
  const client1 = await auth('client1@swift.com');
  const client2 = await auth('client2@swift.com');
  const client3 = await auth('client3@swift.com');
  const courier1 = await auth('courier1@swift.com');
  const courier2 = await auth('courier2@swift.com');

  const couriers = (await api('/admin/users/couriers', admin)).body;
  const list = couriers.content || couriers;
  const c1 = list.find((u) => u.email === 'courier1@swift.com');
  const c2 = list.find((u) => u.email === 'courier2@swift.com');

  const made = [];
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e).slice(0, 160)));

  const login = async (email) => {
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
  };

  const mapHeight = async (scope) => {
    const box = await scope.locator('.leaflet-container').first().boundingBox();
    return box ? Math.round(box.height) : null;
  };

  try {
    // ---- set up three orders in the three relevant states ----
    console.log('\n-- setting up probe orders --');
    const inTransit = await createDelivery(client1, 'in transit');
    made.push(inTransit);
    await assign(admin, inTransit, c1.id);
    const started = await setStatus(courier1, inTransit, 'IN_TRANSIT');
    check('probe order reached IN_TRANSIT', started.ok, `status ${started.status}`);

    const assigned = await createDelivery(client2, 'assigned');
    made.push(assigned);
    const asg = await assign(admin, assigned, c2.id);
    check('probe order reached ASSIGNED', asg.ok, `status ${asg.status}`);

    const pending = await createDelivery(client3, 'pending');
    made.push(pending);

    // ---- client1: the active, in-transit order ----
    console.log('\n-- client1 sees the always-visible map --');
    await login('client1@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const section = page.locator('[data-testid="client-live-map"]');
    await section.waitFor({ state: 'visible', timeout: 40000 });
    check('inline live map is visible for the active order', await section.isVisible());

    const map = section.locator('.leaflet-container');
    await map.first().waitFor({ state: 'visible', timeout: 40000 });
    check('the Leaflet map actually rendered inside the section', (await map.count()) === 1);

    const h1 = await mapHeight(section);
    check('the map is a section, not the 500px default or full page',
      h1 !== null && h1 >= 280 && h1 <= 400, h1 !== null ? `${h1}px` : 'no box');

    const sectionBox = await section.boundingBox();
    check('the whole section fits comfortably in the viewport',
      !!sectionBox && sectionBox.height <= 520,
      sectionBox ? `${Math.round(sectionBox.height)}px` : 'no box');

    const before = (await page.locator('[data-testid="client-live-map-status"]').innerText()).trim();
    check('before any broadcast the map reads connecting, not live and not lost',
      /Connecting/.test(before) && !/Live/.test(before) && !/Signal lost/.test(before), before);

    // ---- broadcast, then watch it go live ----
    console.log('\n-- courier broadcasts a fix --');
    const sent = await api(`/courier/deliveries/${inTransit}/location`, courier1, {
      method: 'PATCH',
      body: JSON.stringify(BROADCAST),
    });
    check('the courier broadcast is accepted', sent.status === 200 || sent.status === 204,
      `status ${sent.status} ${sent.body?.message || ''}`);

    await page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="client-live-map-status"]');
      return el && /Live/.test(el.textContent);
    }, { timeout: 25000 }).catch(() => {});

    const after = (await page.locator('[data-testid="client-live-map-status"]').innerText()).trim();
    check('the client map turns live after the broadcast', /Live/.test(after), after);
    check('the courier marker is drawn on the map',
      (await section.getByText('Courier', { exact: true }).count()) > 0);
    check('no page error while the map polls', errors.length === 0, errors[0] || '');

    // ---- client2: assigned but not yet moving ----
    console.log('\n-- client2 sees the map as soon as a courier is assigned --');
    await login('client2@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const section2 = page.locator('[data-testid="client-live-map"]');
    await section2.waitFor({ state: 'visible', timeout: 40000 });
    check('map appears for an ASSIGNED order', await section2.isVisible());
    const h2 = await mapHeight(section2);
    check('the assigned map is bounded too',
      h2 !== null && h2 >= 280 && h2 <= 400, h2 !== null ? `${h2}px` : 'no box');

    // ---- client3: nothing active ----
    console.log('\n-- client3, whose order is still PENDING, sees no map --');
    await login('client3@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(3000);
    check('no live map for a client with no active order',
      (await page.locator('[data-testid="client-live-map"]').count()) === 0);

    // ---- the map's data source still enforces ownership ----
    const stolen = await api(`/client/deliveries/${inTransit}/location`, client3);
    check('another client cannot read this order at all (IDOR)',
      !stolen.ok && [400, 403, 404].includes(stolen.status),
      `status ${stolen.status} ${stolen.body?.message || ''}`);
  } catch (e) {
    check('threw: ' + String(e).split('\n')[0].slice(0, 160), false);
  } finally {
    console.log('\n-- cleanup --');
    for (const id of made) {
      const r = await api(`/admin/deliveries/${id}`, admin, { method: 'DELETE' });
      console.log(`   ${id.slice(0, 8)} deleted: ${r.status === 204 || r.ok} (${r.status})`);
    }
    await browser.close();
  }

  console.log('\n' + '='.repeat(72));
  if (failures.length === 0) {
    console.log(`ALL CLIENT MAP CHECKS OK (${passed})`);
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
