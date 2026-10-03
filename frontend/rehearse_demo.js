/**
 * Full "in the flesh" rehearsal, in the corrected run-of-show order.
 *
 * WHY THIS EXISTS
 * DEMO.md lists measured durations per segment, but the segments had only ever
 * been measured in isolation. What was never validated is the *chain*: security
 * first (it logs admin out), then a fresh booking, then live tracking, then the
 * fraud panel, then the assistant — with the account switches, logins and page
 * loads that a presenter actually performs. This script plays that exact
 * sequence against the running local stack, drives the real UI where a human
 * would, runs the real verification scripts where a human would, and timestamps
 * every phase so the gaps ("transitions") are counted instead of assumed.
 *
 * It is a rehearsal harness, not a product test. The product assertions live in
 * verify_*.js; this file only proves the ordering and measures the clock.
 *
 * RUN
 *   cd frontend
 *   node rehearse_demo.js
 *
 * Requires the local stack up (docker compose up -d), OSRM reachable and the
 * Ollama model available for the assistant phase.
 */

const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');

const WEB = 'http://localhost:3000';
const API = 'http://localhost:8080/api/v1';
const PASSWORD = 'password123';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();
const secs = (ms) => (ms / 1000).toFixed(1);

let T0 = now();
const stamp = () => secs(now() - T0);

const phases = [];
const results = [];

function startPhase(name) {
  const p = { name, t: now(), ms: 0, detail: '' };
  phases.push(p);
  console.log(`\n[${stamp().padStart(6)}s] ===== PHASE: ${name} =====`);
  return p;
}

function endPhase(p, detail = '') {
  p.ms = now() - p.t;
  p.detail = detail;
  console.log(`[${stamp().padStart(6)}s] ----- end ${p.name}: ${secs(p.ms)}s  ${detail}`);
}

function check(name, pass, detail = '') {
  results.push({ name, pass, detail });
  console.log(`  ${pass ? 'OK  ' : 'KO  '} ${name}${detail ? `  (${detail})` : ''}`);
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

async function api(p, headers, options = {}) {
  const r = await fetch(`${API}${p}`, {
    ...options,
    headers: { ...headers, ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  const body = r.status === 204 ? null : await r.json().catch(() => null);
  return { status: r.status, ok: r.ok, body };
}

/** Run a sibling verify_*.js as a child and stream its output through. */
function runScript(file, env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(__dirname, file)], {
      cwd: __dirname,
      env: { ...process.env, ...env },
    });
    let out = '';
    child.stdout.on('data', (d) => { out += d; process.stdout.write(String(d)); });
    child.stderr.on('data', (d) => { out += d; process.stderr.write(String(d)); });
    child.on('close', (code) => resolve({ code, out }));
    child.on('error', reject);
  });
}

async function login(page, email) {
  await page.goto(`${WEB}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.goto(`${WEB}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.waitForSelector('#login-email', { timeout: 120000 });
  // Wait for hydration, not just the button: the SSR HTML already has an
  // enabled submit and clicking it before React attaches does nothing.
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

/**
 * Set one LocationPicker by clicking the map.
 *
 * The map click is deliberate: the free-text geocoder is ambiguous. In
 * rehearsal, typing "Centre, Casablanca" resolved to the Centre region of
 * CAMEROON, the server then refused the route as over the 45-minute limit, and
 * checkout never opened. A map click sets the coordinates directly inside the
 * city and the reverse geocoder fills the address field (the backend requires a
 * non-blank address). This is the reliable presenter path and the one the
 * runbook should tell a human to use.
 */
async function setLocation(dialog, index) {
  const input = dialog.getByPlaceholder('Search or click the map').nth(index);
  const maps = dialog.locator('.leaflet-container');
  const box = await maps.nth(index).boundingBox();
  const x = index === 0 ? 40 : Math.max(40, Math.floor(box.width - 40));
  const y = Math.max(20, Math.floor(box.height - 24));
  await maps.nth(index).click({ position: { x, y } });
  // Wait for the reverse geocode to populate the address box; do not block the
  // quote forever if it is slow — the coordinates are already set.
  for (let i = 0; i < 25; i++) {
    if ((await input.inputValue()).trim()) break;
    await sleep(200);
  }
  return (await input.inputValue()).trim() || '(address not reverse-geocoded)';
}

async function findCourierFor(orderId, timeoutMs = 25000) {
  const emails = ['courier1@swift.com', 'courier2@swift.com'];
  const t = now();
  while (now() - t < timeoutMs) {
    for (const email of emails) {
      const h = await auth(email);
      const r = await api('/courier/deliveries', h);
      if (r.ok && Array.isArray(r.body) && r.body.some((d) => d.id === orderId)) return email;
    }
    await sleep(1000);
  }
  return null;
}

async function preflight() {
  const health = await fetch(`${API}/health`).then((r) => r.json()).catch(() => null);
  console.log(`  backend health: ${health ? JSON.stringify(health).slice(0, 120) : 'UNREACHABLE'}`);
  const osrm = await fetch('http://localhost:5000/route/v1/driving/-7.6311,33.5891;-7.5898,33.5731?overview=false')
    .then((r) => r.json()).catch(() => null);
  console.log(`  osrm: ${osrm?.code || 'UNREACHABLE'}`);
  return { health, osrm };
}

async function main() {
  console.log('='.repeat(72));
  console.log('SwiftDeliver — full rehearsal, corrected order');
  console.log('security -> fresh booking -> live -> fraud -> assistant');
  console.log('='.repeat(72));

  console.log('\n[pre] stack preflight');
  await preflight();

  // Step 0 of the runbook is not shown during the demo: it warms the assistant
  // so the timed assistant phase reflects a warm model, exactly as rehearsed.
  console.log('\n[pre] step 0 — warming the assistant (not part of the timed demo)');
  try {
    const client = await auth('client1@swift.com');
    const t = now();
    const r = await api('/client/chat', client, {
      method: 'POST',
      body: JSON.stringify({ message: 'How is the delivery price calculated?' }),
    });
    console.log(`[pre] assistant warm-up: HTTP ${r.status} in ${secs(now() - t)}s`);
  } catch (e) {
    console.log(`[pre] assistant warm-up failed (assistant may be offline): ${e.message}`);
  }

  T0 = now();
  const browser = await chromium.launch();
  let clientPage = null;
  let orderId = null;

  try {
    // ================= 1. SECURITY (first, on purpose) =====================
    const pSec = startPhase('1. Security (auth hardening + delivery proof)');
    const tAuth = now();
    const authRes = await runScript('verify_auth_hardening.js');
    const authMs = now() - tAuth;
    const tProof = now();
    const proofRes = await runScript('verify_proof.js');
    const proofMs = now() - tProof;
    check('verify_auth_hardening.js exits 0', authRes.code === 0, `exit ${authRes.code} in ${secs(authMs)}s`);
    check('verify_proof.js exits 0', proofRes.code === 0, `exit ${proofRes.code} in ${secs(proofMs)}s`);
    endPhase(pSec, `auth ${secs(authMs)}s + proof ${secs(proofMs)}s`);

    // ================= 2. FRESH BOOKING + AUTO-DISPATCH ====================
    const pBook = startPhase('2. Fresh booking + auto-dispatch (client1 UI)');
    const ctxClient = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    clientPage = await ctxClient.newPage();
    clientPage.on('pageerror', (e) => console.log('  [client pageerror]', String(e).slice(0, 120)));

    const desc = `Rehearsal ${new Date().toISOString().slice(11, 19)}`;
    await login(clientPage, 'client1@swift.com');
    await clientPage.goto(`${WEB}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    await clientPage.getByRole('button', { name: 'New delivery' }).click();

    const dlg = clientPage.locator('[role=dialog][aria-label="New delivery request"]');
    await dlg.waitFor({ state: 'visible', timeout: 30000 });
    await dlg.getByPlaceholder('e.g. 2 small boxes').fill(desc);
    const l1 = await setLocation(dlg, 0);
    const l2 = await setLocation(dlg, 1);
    check('both pickup and dropoff were set by clicking the map',
      !!l1 && !!l2 && l1 !== '(address not reverse-geocoded)' && l2 !== '(address not reverse-geocoded)',
      `pickup="${l1.slice(0, 26)}", dropoff="${l2.slice(0, 26)}"`);

    const tQuote = now();
    await dlg.getByRole('button', { name: 'Continue to payment' }).click();
    const pay = clientPage.locator('[role=dialog][aria-label="Secure Checkout"]');
    await pay.waitFor({ state: 'visible', timeout: 30000 });
    const quoteMs = now() - tQuote;
    const amountText = await pay.getByText(/MAD$/).first().innerText();
    check('the server priced the route and opened checkout', true, `${amountText.trim()} in ${secs(quoteMs)}s`);

    await pay.getByPlaceholder('John Doe').fill('Demo User');
    await pay.getByPlaceholder('0000 0000 0000 0000').fill('4242424242424242');
    await pay.getByPlaceholder('MM/YY').fill('12/30');
    await pay.getByPlaceholder('123').fill('123');
    const tPay = now();
    await pay.getByRole('button', { name: /^Pay / }).click();
    await clientPage.locator('tr', { hasText: desc }).first().waitFor({ state: 'visible', timeout: 40000 });
    check('payment captured and the order appears in the list', true, `${secs(now() - tPay)}s`);

    const clientHeaders = await auth('client1@swift.com');
    let order = null;
    let status = null;
    const tDispatch = now();
    while (now() - tDispatch < 25000) {
      const r = await api('/client/deliveries', clientHeaders);
      order = (r.body || []).find((d) => d.description === desc);
      status = order?.status;
      if (status === 'ASSIGNED' || status === 'IN_TRANSIT') break;
      await sleep(1000);
    }
    orderId = order?.id || null;
    check('auto-dispatch assigned a courier after payment',
      status === 'ASSIGNED' || status === 'IN_TRANSIT',
      `status=${status} in ${secs(now() - tDispatch)}s, id=${orderId ? orderId.slice(0, 8) : 'none'}`);
    endPhase(pBook, `quote ${secs(quoteMs)}s, dispatch ${secs(now() - tDispatch)}s`);

    // ================= 3. LIVE TRACKING ====================================
    const pLive = startPhase('3. Live tracking (courier starts, client goes Live)');
    const courierEmail = orderId ? await findCourierFor(orderId) : null;
    check('the fresh order sits on one courier', !!courierEmail, courierEmail || 'not found');

    if (courierEmail && clientPage) {
      const ctxCourier = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      const courierPage = await ctxCourier.newPage();
      await login(courierPage, courierEmail);
      await courierPage.goto(`${WEB}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
      const cRow = courierPage.locator('tr', { hasText: desc }).first();
      await cRow.waitFor({ state: 'visible', timeout: 30000 });
      const tStart = now();
      await cRow.getByRole('button', { name: 'Start delivery' }).click();

      // The laptop must keep the deterministic simulated route. If the device
      // default ever flips, the courier would silently open a real GPS watch
      // (a location prompt) during the automated run.
      let courierMode = 'not shown';
      try {
        await courierPage.getByText('Simulating GPS').waitFor({ state: 'visible', timeout: 15000 });
        courierMode = 'Simulating GPS';
      } catch {
        courierMode = await courierPage
          .locator('header span', { hasText: /Simulating GPS|live location/ })
          .first()
          .innerText()
          .catch(() => 'not shown');
      }
      check('courier defaults to the simulated route on the laptop', courierMode === 'Simulating GPS', courierMode);

      await clientPage.waitForFunction(() => {
        const el = document.querySelector('[data-testid="client-live-map-status"]');
        return el && /Live/.test(el.textContent);
      }, null, { timeout: 90000 });
      const liveMs = now() - tStart;
      const sub = await clientPage.locator('[data-testid="client-live-map"] p').first().innerText();
      check('the client live section tracks THIS fresh order', sub.includes(desc), sub.slice(0, 70));
      check('the client map reads Live once the courier broadcasts', true, `${secs(liveMs)}s from Start`);
      await courierPage.close();
      await ctxCourier.close();
    }
    endPhase(pLive, `assigned to ${courierEmail || '—'}`);

    // ================= 4. AI FRAUD =========================================
    const pFraud = startPhase('4. AI fraud (scripted trajectories + fleet panel)');
    const tFraud = now();
    const fraudRes = await runScript('verify_fraud_panel.js', { KEEP: '1' });
    const fraudMs = now() - tFraud;
    check('verify_fraud_panel.js exits 0', fraudRes.code === 0, `exit ${fraudRes.code} in ${secs(fraudMs)}s`);

    const ctxAdmin = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const adminPage = await ctxAdmin.newPage();
    await login(adminPage, 'admin@swift.com');
    await adminPage.goto(`${WEB}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    await adminPage.waitForTimeout(9000); // first paint + the trail fetch

    const fraudRow = adminPage.locator('tr', { hasText: 'PANEL-FRAUD' }).first();
    await fraudRow.waitFor({ state: 'visible', timeout: 30000 });
    check('the fleet panel badges the impossible trajectory as AI alert',
      (await fraudRow.locator('button', { hasText: /AI alert/ }).count()) > 0);
    const honestRow = adminPage.locator('tr', { hasText: 'PANEL-HONEST' }).first();
    check('the honest trajectory is badged AI ok, not AI alert',
      (await honestRow.locator('button', { hasText: /AI ok/ }).count()) > 0 &&
      (await honestRow.locator('button', { hasText: /AI alert/ }).count()) === 0);

    await fraudRow.locator('button', { hasText: /AI alert/ }).first().click();
    const trailModal = adminPage.locator('[role=dialog]').filter({ hasText: /Trajectory risk/i });
    await trailModal.waitFor({ state: 'visible', timeout: 30000 });
    await adminPage.waitForTimeout(2500);
    check('the score-curve modal opens from the tag',
      (await trailModal.locator('polyline').count()) > 0);
    await adminPage.keyboard.press('Escape');
    await adminPage.waitForTimeout(800);
    endPhase(pFraud, `script ${secs(fraudMs)}s, panel shown`);
    await ctxAdmin.close();

    // ================= 5. ASSISTANT ========================================
    const pAsst = startPhase('5. Assistant (warm model, running product)');
    await clientPage.bringToFront();
    await clientPage.click('[aria-label="Open the delivery assistant"]');
    const chatInput = clientPage.locator('[aria-label="Message the assistant"]');
    const q = 'What is the refund policy if my delivery is late?';
    await chatInput.fill(q);
    const bubbles = '[aria-label="Delivery assistant"] .justify-start .whitespace-pre-wrap';
    const before = await clientPage.locator(bubbles).count();
    const tAsk = now();
    await clientPage.click('[aria-label="Send message"]');
    // Count actual message bubbles, not the typing indicator: the indicator is
    // also a justify-start child but carries no text, and counting it made the
    // check pass on an empty answer in 0.4 s.
    await clientPage.waitForFunction(
      (n) => document.querySelectorAll('[aria-label="Delivery assistant"] .justify-start .whitespace-pre-wrap').length > n,
      before,
      { timeout: 120000 },
    );
    const answer = (await clientPage.locator(bubbles).last().innerText()).trim();
    check('the assistant answered in the running product', answer.length > 20, `${secs(now() - tAsk)}s`);
    console.log(`      Q: ${q}`);
    console.log(`      A: ${answer.replace(/\s+/g, ' ').slice(0, 170)}`);
    endPhase(pAsst, `${secs(now() - tAsk)}s`);

    // ================= cleanup =============================================
    console.log('\n-- cleanup (rehearsal only: the demo leaves the panel up) --');
    const admin = await auth('admin@swift.com');
    const ids = new Set();
    const m = fraudRes.out.match(/KEEP_IDS=([^\r\n]+)/);
    if (m) m[1].split(',').map((s) => s.trim()).filter(Boolean).forEach((id) => ids.add(id));
    if (orderId) ids.add(orderId);
    for (const id of ids) {
      const r = await api(`/admin/deliveries/${id}`, admin, { method: 'DELETE' });
      console.log(`   deleted ${id.slice(0, 8)}: HTTP ${r.status}`);
    }
  } catch (e) {
    check('rehearsal threw: ' + String(e).split('\n')[0].slice(0, 140), false);
  } finally {
    await browser.close();
  }

  // ================= summary =============================================
  const elapsed = now() - T0;
  console.log('\n' + '='.repeat(72));
  console.log('TIMELINE — mechanical wall clock, transitions included');
  let sum = 0;
  for (const p of phases) {
    sum += p.ms;
    console.log(`  ${p.name.padEnd(52)} ${secs(p.ms).padStart(6)}s  ${p.detail}`);
  }
  console.log(`  ${'sum of phases'.padEnd(52)} ${secs(sum).padStart(6)}s`);
  console.log(`  ${'total elapsed'.padEnd(52)} ${secs(elapsed).padStart(6)}s`);
  console.log('  (the demo adds the presenter talking; the runbook budgets ~8.5 min)');

  const ko = results.filter((r) => !r.pass).length;
  console.log('\n' + '='.repeat(72));
  if (ko === 0) console.log(`REHEARSAL OK (${results.length} checks)`);
  else {
    console.log(`${ko} FAILURE(S) of ${results.length}:`);
    results.filter((r) => !r.pass).forEach((r) => console.log(`  - ${r.name} (${r.detail})`));
  }
  process.exit(ko === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('\nrehearsal failed:', e.message);
  process.exit(1);
});
