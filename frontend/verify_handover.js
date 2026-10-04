/**
 * Does the handover-code flow seal a delivery without GPS proximity?
 *
 * WHAT THIS CHECKS
 * The courier advances an order to ARRIVED (the door step), then confirms it
 * by typing the six digits the client shows, with no position anywhere near
 * the drop-off. It asserts: (1) ASSIGNED cannot skip to DELIVERED; (2) the
 * client Code modal shows a QR plus the exact code the API issued; (3) a wrong
 * code is refused and the order is unchanged (fail closed, no silent GPS
 * fallback); (4) the right code seals the delivery with codeVerified=true and
 * a 64-char HMAC. The GPS-only path is covered by verify_proof.js and must
 * keep passing.
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * Each assertion is a different failure: a mismatched modal code means the UI
 * and the API disagree; an accepted wrong code means fail-open; a sealed order
 * without codeVerified means the factor was not recorded. Nominatim is not
 * involved; the order is created by map-agnostic coordinates.
 *
 * MUTATION
 * Make verifyHandoverCode() always return true in DeliveryProofService.java:
 * "a wrong code is refused" fails. Drop codeVerified from the seal path:
 * "the seal records the code factor" fails.
 *
 * RUN  (backend must be up; WEB points at a frontend built from this tree)
 * node verify_handover.js
 *
 * Uses client2@swift.com throughout so probe orders never appear in client1's
 * live list while a human is testing on the same backend.
 */
const { chromium, devices } = require('playwright');

const BASE = process.env.WEB || 'http://localhost:3000';
const PASSWORD = 'password123';

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

async function api(base, method, path, token, body) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status} on ${method} ${path}`);
    err.status = res.status;
    throw err;
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function signIn(page, email) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
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

async function main() {
  const browser = await chromium.launch();
  let orderId = null;

  try {
    // --- setup: a paid, dispatched order, no position ever broadcast ---
    const tag = 'HANDOVER-E2E-' + Date.now().toString(36).toUpperCase();
    const clientToken = (await api(BASE, 'POST', '/api/v1/auth/login', null, {
      email: 'client2@swift.com', password: PASSWORD,
    })).token;
    const created = await api(BASE, 'POST', '/api/v1/client/deliveries', clientToken, {
      description: tag,
      pickupAddress: 'Anfa Pickup', pickupLat: 33.5891, pickupLng: -7.6311,
      dropoffAddress: 'Anfa Drop', dropoffLat: 33.593, dropoffLng: -7.622,
    });
    orderId = created.id;
    await api(BASE, 'PATCH', `/api/v1/client/deliveries/${orderId}/pay`, clientToken);
    let courierEmail = null;
    for (let i = 0; i < 12; i++) {
      await new Promise((r) => setTimeout(r, 2000));
      const list = await api(BASE, 'GET', '/api/v1/client/deliveries', clientToken);
      const row = list.find((d) => d.id === orderId);
      if (row && row.status === 'ASSIGNED' && row.courierEmail) {
        courierEmail = row.courierEmail;
        break;
      }
    }
    check('the order is paid and dispatched', !!courierEmail, courierEmail || 'never assigned');
    if (!courierEmail) return;

    // Forward-only: jumping straight from ASSIGNED to DELIVERED is refused.
    const guardCourier = (await api(BASE, 'POST', '/api/v1/auth/login', null, {
      email: courierEmail, password: PASSWORD,
    })).token;
    let skipped = false;
    try {
      await api(BASE, 'PATCH', `/api/v1/courier/deliveries/${orderId}/status`, guardCourier, {
        status: 'DELIVERED',
      });
    } catch (e) {
      skipped = e.status === 400;
    }
    check('ASSIGNED cannot skip to DELIVERED', skipped, skipped ? 'HTTP 400' : 'jump accepted (bad)');

    const issued = await api(BASE, 'GET', `/api/v1/client/deliveries/${orderId}/handover-code`, clientToken);
    check('the API issues a six-digit code', /^\d{6}$/.test(issued.code || ''), issued.code);

    // --- 1. client Code modal shows the QR and the same code ---
    // Separate browser contexts: sharing one context means both pages share
    // localStorage, so the courier sign-in below would silently swap the
    // token under the still-open client page (and vice versa), producing
    // 403 noise and races that have nothing to do with the handover flow.
    const clientCtx = await browser.newContext({ ...devices['Pixel 5'] });
    const clientPage = await clientCtx.newPage();
    await signIn(clientPage, 'client2@swift.com');
    await clientPage.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    await clientPage.locator('button:visible', { hasText: 'Code' }).first().click();
    const modalDigits = clientPage.getByLabel(/Handover code \d{6}/);
    await modalDigits.waitFor({ timeout: 30000 });
    const shown = ((await modalDigits.textContent()) || '').replace(/\D/g, '');
    check('the Code modal shows the issued code', shown === issued.code, shown);
    const qrCount = await clientPage.locator('svg').count();
    check('the Code modal draws a QR', qrCount >= 1, `${qrCount} svg(s)`);
    console.log('step: closing Code modal');
    await clientPage.getByRole('button', { name: 'Close dialog' }).click();

    // --- 2 + 3. courier Scan modal: wrong code refused, right code seals ---
    // The owner is re-read right before the courier signs in: an admin (or the
    // dispatcher UI) may have reassigned the order mid-run, and signing in as
    // a stale courier shows an empty round for a reason that has nothing to do
    // with the handover flow.
    async function currentAssignment() {
      const list = await api(BASE, 'GET', '/api/v1/client/deliveries', clientToken);
      return list.find((d) => d.id === orderId) || null;
    }
    console.log('step: signing in courier');
    const courierCtx = await browser.newContext({ ...devices['Pixel 5'] });
    const courierPage = await courierCtx.newPage();
    let assigned = await currentAssignment();
    check('the order still exists before the courier flow', !!assigned, assigned && assigned.status);
    if (!assigned) return;
    await signIn(courierPage, assigned.courierEmail);
    await courierPage.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    // Advance to IN_TRANSIT via the normal button so Scan code appears.
    // The courier starts the delivery first (the real flow: Start at pickup,
    // Scan at the door). IN_TRANSIT also arms the Scan button in the UI.
    const courierToken = (await api(BASE, 'POST', '/api/v1/auth/login', null, {
      email: assigned.courierEmail, password: PASSWORD,
    })).token;
    await api(BASE, 'PATCH', `/api/v1/courier/deliveries/${orderId}/status`, courierToken, {
      status: 'IN_TRANSIT',
    });
    assigned = await currentAssignment();
    check('the order is in transit', assigned && assigned.status === 'IN_TRANSIT', assigned && assigned.status);
    if (!assigned || assigned.status !== 'IN_TRANSIT') return;
    // The courier list is fetched on mount; one long wait beats reloads (a
    // reload only restarts the same fetch and aborts the in-flight one).
    console.log('step: courier UI shows the order');
    // Presence uses attached (not visible): the desktop table stays mounted
    // but hidden on mobile, so a visibility-gated wait would lock onto the
    // hidden twin. Clicks below are scoped to :visible for the same reason.
    let rowVisible = false;
    for (let i = 0; i < 4; i++) {
      try {
        await courierPage.locator('tr, li', { hasText: tag }).first()
          .waitFor({ state: 'attached', timeout: 15000 });
        rowVisible = true;
        break;
      } catch {
        await courierPage.reload({ waitUntil: 'domcontentloaded' });
      }
    }
    check('the courier sees the dispatched order', rowVisible, tag);
    if (!rowVisible) return;
    const startBtn = courierPage.locator('button:visible', { hasText: 'Start delivery' });
    if ((await startBtn.count()) > 0) {
      await startBtn.first().click();
      await courierPage.waitForTimeout(1500);
    }
    // The door step that arms the handover code.
    console.log('step: marking arrived');
    await courierPage.locator('button:visible', { hasText: 'Mark arrived' }).first().click({ timeout: 30000 });
    await courierPage.waitForTimeout(1500);
    const arrived = await api(BASE, 'GET', '/api/v1/client/deliveries', clientToken)
      .then((list) => list.find((d) => d.id === orderId));
    check('the order is arrived', arrived && arrived.status === 'ARRIVED', arrived && arrived.status);
    if (!arrived || arrived.status !== 'ARRIVED') return;
    console.log('step: opening Scan code modal');
    await courierPage.locator('button:visible', { hasText: 'Scan code' }).first().click({ timeout: 30000 });
    const digits = courierPage.getByLabel('Handover code digits');
    await digits.waitFor({ timeout: 30000 });

    console.log('step: typing wrong code');
    await digits.fill('000000');
    await courierPage.getByRole('button', { name: 'Confirm', exact: true }).click();
    await courierPage.waitForTimeout(2000);
    const refusal = await courierPage.getByText(/Invalid handover code/i).count();
    check('a wrong code is refused on screen', refusal >= 1, `${refusal} refusal(s)`);
    const stillThere = await api(BASE, 'GET', '/api/v1/client/deliveries', clientToken)
      .then((list) => list.find((d) => d.id === orderId));
    check('the refused order is unchanged', stillThere && stillThere.status === 'ARRIVED', stillThere && stillThere.status);

    await digits.fill(issued.code);
    await courierPage.getByRole('button', { name: 'Confirm', exact: true }).click();
    await courierPage.waitForTimeout(3000);
    const sealed = await api(BASE, 'GET', '/api/v1/client/deliveries', clientToken)
      .then((list) => list.find((d) => d.id === orderId));
    check(
      'the right code seals the delivery by code factor',
      sealed && sealed.status === 'DELIVERED' && sealed.codeVerified === true
        && typeof sealed.proofHash === 'string' && sealed.proofHash.length === 64,
      sealed ? `${sealed.status} codeVerified=${sealed.codeVerified}` : 'order missing',
    );

    await clientCtx.close();
    await courierCtx.close();
  } catch (e) {
    check('threw: ' + String(e).split('\n')[0].slice(0, 160), false);
  } finally {
    // Cleanup the probe order whatever happened.
    try {
      if (orderId) {
        const adm = (await api(BASE, 'POST', '/api/v1/auth/login', null, {
          email: 'admin@swift.com', password: PASSWORD,
        })).token;
        await api(BASE, 'DELETE', `/api/v1/admin/deliveries/${orderId}`, adm);
      }
    } catch { /* already gone */ }
    await browser.close();
  }

  console.log('\n' + '='.repeat(72));
  if (failures.length === 0) {
    console.log(`ALL HANDOVER CHECKS OK (${passed})`);
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
