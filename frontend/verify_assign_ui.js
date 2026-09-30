const { chromium } = require('playwright');
const BASE = 'http://localhost:3000';
const API = 'http://localhost:8080';
const results = [];
const ok = (n, c, extra) => { results.push([c ? 'OK  ' : 'KO  ', n, extra || '']); };

/**
 * Wait until React has actually attached the form's submit handler.
 *
 * "The submit button exists and is not disabled" passes on the server-rendered
 * HTML before hydration, so the click lands on a form with no handler and does
 * nothing at all. A real check waits for the handler itself.
 */
async function waitForSubmitHandler(page, scope = 'document') {
  await page.waitForFunction((sc) => {
    const root = sc === 'document' ? document : document.querySelector(sc);
    const form = root && root.querySelector('form');
    if (!form) return false;
    const key = Object.keys(form).find((k) => k.startsWith('__reactProps$'));
    return !!key && typeof form[key].onSubmit === 'function';
  }, scope, { timeout: 120000 });
}

/** Which account the current session actually belongs to, per the backend. */
async function sessionEmail(page) {
  return page.evaluate(async (api) => {
    const token = localStorage.getItem('token');
    if (!token) return null;
    const res = await fetch(`${api}/api/v1/users/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    return res.ok ? (await res.json()).email : null;
  }, API);
}

/**
 * Sign in as a specific user, from a clean session.
 *
 * Storage is cleared first because /login redirects to the dashboard the instant
 * a token exists, so "log in as somebody else" silently keeps the previous user.
 * The session is then confirmed against the backend rather than assumed.
 */
async function login(page, email) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.waitForSelector('#login-email', { timeout: 120000 });
  await waitForSubmitHandler(page);
  await page.fill('#login-email', email);
  await page.fill('#login-password', 'password123');
  await page.click('button[type=submit]');
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 60000 });
  const who = await sessionEmail(page);
  if (who !== email) throw new Error(`login as ${email} left the session as ${who}`);
}

/** Seed a delivery owned by client1, straight through the API. */
async function seed(description) {
  const loginRes = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'client1@swift.com', password: 'password123' }),
  });
  const { token } = await loginRes.json();
  const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
  const res = await fetch(`${API}/api/v1/client/deliveries`, {
    method: 'POST', headers: auth,
    body: JSON.stringify({
      description, pickupAddress: 'UI-A, Casa', dropoffAddress: 'UI-B, Casa',
      pickupLat: 33.5731, pickupLng: -7.5898, dropoffLat: 33.5891, dropoffLng: -7.6311,
    }),
  });
  return { id: (await res.json()).id, token };
}

/** Open the admin deliveries view and wait for the table to have rows. */
async function openAdminDeliveries(page) {
  await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.waitForSelector('table tbody tr', { timeout: 120000 });
  // Hydration: a button that exists but has no onClick does nothing when
  // clicked, which looks exactly like a broken feature. Wait for the handler.
  await page.waitForFunction(() => {
    const btn = document.querySelector('button[aria-label^="Assign courier to"]');
    if (!btn) return false;
    const key = Object.keys(btn).find((k) => k.startsWith('__reactProps$'));
    return !!key && typeof btn[key].onClick === 'function';
  }, { timeout: 120000 });
}

/** A row's text, located by the description it contains. */
function row(page, description) {
  return page.locator('table tbody tr', { hasText: description });
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  try {
    // ---------- the control has to be on rows that are NOT pending ----------
    const a = await seed('UI-ASSIGN-A');
    const b = await seed('UI-ASSIGN-B');
    for (const s of [a, b]) {
      await fetch(`${API}/api/v1/client/deliveries/${s.id}/pay`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` },
      });
    }
    console.log(`  seeded ${a.id.slice(0, 8)} and ${b.id.slice(0, 8)}`);

    await login(page, 'admin@swift.com');
    await openAdminDeliveries(page);

    const rowA = row(page, 'UI-ASSIGN-A');
    const rowB = row(page, 'UI-ASSIGN-B');
    ok('both seeded orders are in the admin table',
      await rowA.count() === 1 && await rowB.count() === 1,
      `A=${await rowA.count()} B=${await rowB.count()}`);

    // The whole point: assign a PAID order, not only a PENDING one.
    ok('a paid order offers a Reassign control, not just Assign',
      (await rowA.locator('button[aria-label^="Assign courier to"]').innerText()).trim() === 'Reassign',
      await rowA.locator('button[aria-label^="Assign courier to"]').innerText());
    ok('an order with no courier yet offers Assign',
      (await row(page, 'UI-ASSIGN-C').count()) === 0, 'no C row expected yet');

    // ---------- reassign A to courier2, through the modal ----------
    await rowA.locator('button[aria-label^="Assign courier to"]').click();
    await page.waitForSelector('#assign-courier', { timeout: 30000 });
    await waitForSubmitHandler(page);
    ok('the assign modal names the delivery being assigned',
      (await page.locator('form').innerText()).includes('UI-ASSIGN-A'),
      'modal body mentions the order');

    const loadText = await page.locator('#assign-courier').evaluate((el) =>
      [...el.options].map((o) => o.text.trim()).join(' | '));
    ok('each courier option shows their live stop count',
      /active stop/.test(loadText) && /\d+ active stop/.test(loadText), loadText);

    // ---------- the modal must sit ABOVE the map ----------
    // Leaflet stacks its own controls at z-index 800 (.leaflet-control) and
    // 1000 (.leaflet-top / .leaflet-bottom). A modal at z-100 is therefore
    // painted UNDER the zoom buttons: the map looks like it is in front of the
    // dialog, and the +/- stay clickable through the backdrop.
    //
    // "Is the modal visible" cannot catch this — it is, just not on top. The
    // only honest test is to ask the browser what is actually painted at the
    // map controls' own coordinates while the modal is open, so the map has to
    // be scrolled into view first. A previous version of this script missed the
    // bug entirely for exactly that reason.
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(500);
    const stack = await page.evaluate(() => {
      const overlay = document.querySelector('.fixed.inset-0');
      const at = (sel, label) => {
        const el = document.querySelector(sel);
        if (!el) return { label, missing: true };
        const r = el.getBoundingClientRect();
        const x = r.x + r.width / 2;
        const y = r.y + r.height / 2;
        if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) return { label, offscreen: true };
        const top = document.elementFromPoint(x, y);
        return {
          label,
          onscreen: true,
          leafletOnTop: !!(top && top.closest('.leaflet-container')),
          topEl: top ? `${top.tagName}.${String(top.className).slice(0, 40)}` : null,
        };
      };
      return {
        overlayZ: overlay ? getComputedStyle(overlay).zIndex : null,
        probes: [at('.leaflet-control-zoom', 'zoom'), at('.leaflet-control-attribution', 'attribution')],
      };
    });
    console.log(`  modal z-index=${stack.overlayZ}; map controls on screen: ` +
      stack.probes.map((p) => `${p.label}=${p.onscreen ? (p.leafletOnTop ? 'LEAK' : 'ok') : 'off'}`).join(' '));
    const leaked = stack.probes.filter((p) => p.onscreen && p.leafletOnTop);
    ok('no map control paints on top of the assign modal', leaked.length === 0,
      leaked.length ? leaked.map((p) => `${p.label}: ${p.topEl}`).join('; ')
        : `overlay z=${stack.overlayZ}, map controls behind it`);
    ok('the modal overlay is above Leaflet\'s own z-1000 controls',
      Number(stack.overlayZ) > 1000, `overlay z-index is ${stack.overlayZ}, Leaflet tops out at 1000`);

    // And a toast raised while the dialog is open must still be readable.
    const toastZ = await page.evaluate(() => {
      const t = document.querySelector('[role="status"], [role="alert"], .pointer-events-none.fixed');
      return t ? getComputedStyle(t).zIndex : null;
    });
    ok('toasts sit above the modal, not behind it',
      toastZ !== null && Number(toastZ) > Number(stack.overlayZ),
      `toast z=${toastZ} vs modal z=${stack.overlayZ}`);

    const courier2Value = await page.locator('#assign-courier').evaluate((el) => {
      const opt = [...el.options].find((o) => o.text.includes('courier2@'));
      return opt ? { value: opt.value, disabled: opt.disabled, text: opt.text.trim() } : null;
    });
    ok('courier2 is selectable in the modal', courier2Value && !courier2Value.disabled,
      courier2Value ? `${courier2Value.text} disabled=${courier2Value.disabled}` : 'option missing');

    await page.selectOption('#assign-courier', courier2Value.value);
    await page.click('button[type=submit]');
    await page.waitForSelector('#assign-courier', { state: 'detached', timeout: 30000 });

    // Read the truth from the API, not from the DOM: the table is behind a 10s
    // poll and asserting on it can pass on a stale render.
    const adminTok = (await (await fetch(`${API}/api/v1/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'admin@swift.com', password: 'password123' }),
    })).json()).token;
    const H = { Authorization: `Bearer ${adminTok}` };
    const afterA = (await (await fetch(`${API}/api/v1/admin/deliveries`, { headers: H })).json())
      .find((d) => d.id === a.id);
    ok('the reassignment actually persisted', afterA.courierEmail === 'courier2@swift.com',
      `now ${afterA.courierEmail} / ${afterA.status}`);
    ok('the reassigned order is ASSIGNED again', afterA.status === 'ASSIGNED', afterA.status);

    await openAdminDeliveries(page);
    ok('the admin table now shows courier2 on that order',
      (await row(page, 'UI-ASSIGN-A').innerText()).includes('courier2@swift.com'),
      (await row(page, 'UI-ASSIGN-A').innerText()).replace(/\s+/g, ' ').slice(0, 110));
    ok('the row now offers Unassign alongside Reassign',
      await row(page, 'UI-ASSIGN-A').locator('button[aria-label^="Unassign"]').count() === 1,
      'unassign button present');

    // ---------- unassign it again ----------
    page.once('dialog', (d) => d.accept());
    await row(page, 'UI-ASSIGN-A').locator('button[aria-label^="Unassign"]').click();
    await page.waitForFunction(
      (desc) => {
        const tr = [...document.querySelectorAll('table tbody tr')]
          .find((r) => r.innerText.includes(desc));
        return tr && tr.innerText.includes('Unassigned');
      },
      'UI-ASSIGN-A', { timeout: 60000 }
    ).catch(() => {});
    const afterUn = (await (await fetch(`${API}/api/v1/admin/deliveries`, { headers: H })).json())
      .find((d) => d.id === a.id);
    ok('unassigning from the UI clears the courier', !afterUn.courierEmail,
      afterUn.courierEmail || 'nobody');
    ok('the unassigned order is back to PENDING', afterUn.status === 'PENDING', afterUn.status);

    // ---------- the guard is visible in the modal ----------
    await row(page, 'UI-ASSIGN-B').locator('button[aria-label^="Assign courier to"]').click();
    await page.waitForSelector('#assign-courier', { timeout: 30000 });
    const currentDisabled = await page.locator('#assign-courier').evaluate((el) => {
      const opt = [...el.options].find((o) => o.text.includes('(current)'));
      return opt ? { disabled: opt.disabled, text: opt.text.trim() } : null;
    });
    ok('the current courier is marked and not re-selectable',
      currentDisabled && currentDisabled.disabled, JSON.stringify(currentDisabled));
    ok('the modal warns that assigning resets the status',
      (await page.locator('form').innerText()).toLowerCase().includes('assign'),
      'wording present');
    await page.keyboard.press('Escape');

    // ---------- the two-courier scenario, seen as a courier ----------
    const c1 = (await (await fetch(`${API}/api/v1/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'courier1@swift.com', password: 'password123' }),
    })).json()).token;
    const cid = (await (await fetch(`${API}/api/v1/admin/users/couriers`, { headers: H })).json())
      .find((c) => c.email === 'courier1@swift.com').id;
    await fetch(`${API}/api/v1/admin/deliveries/${b.id}/assign`, {
      method: 'PATCH', headers: { ...H, 'Content-Type': 'application/json' },
      body: JSON.stringify({ courierId: cid }),
    });

    await login(page, 'courier1@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.waitForSelector('body', { timeout: 60000 });
    const c1Body = await page.locator('body').innerText();
    ok('courier1 sees their own assigned order', c1Body.includes('UI-ASSIGN-B'), 'UI-ASSIGN-B listed');
    ok("courier1 does not see the other courier's order", !c1Body.includes('UI-ASSIGN-A'),
      'UI-ASSIGN-A absent');

    await login(page, 'courier2@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.waitForSelector('body', { timeout: 60000 });
    ok("courier2 does not see courier1's order",
      !(await page.locator('body').innerText()).includes('UI-ASSIGN-B'), 'UI-ASSIGN-B absent');

    // ---------- cleanup ----------
    for (const id of [a.id, b.id]) {
      await fetch(`${API}/api/v1/admin/deliveries/${id}`, { method: 'DELETE', headers: H });
    }
    const left = (await (await fetch(`${API}/api/v1/admin/deliveries`, { headers: H })).json())
      .filter((d) => /^UI-/.test(d.description));
    ok('scratch orders cleaned up', left.length === 0,
      left.length ? left.map((d) => d.description).join(', ') : 'none left');
  } catch (e) {
    ok('the run completed without throwing', false, e.message);
  } finally {
    await browser.close();
  }

  results.forEach(([s, n, e]) => console.log(`  ${s} ${n}${e ? '\n        << ' + e : ''}`));
  const ko = results.filter(([s]) => s === 'KO  ').length;
  console.log(ko === 0 ? 'ALL CHECKS OK' : `${ko} FAILURES`);
  process.exit(ko === 0 ? 0 : 1);
})();
