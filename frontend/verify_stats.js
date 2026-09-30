const { chromium } = require('playwright');
const BASE = 'http://localhost:3000';
const results = [];
const ok = (n, c, extra) => { results.push([c ? 'OK  ' : 'KO  ', n, extra || '']); };

/**
 * Wait until React has actually attached the form's submit handler.
 *
 * The obvious check — "the submit button exists and is not disabled" — passes on
 * the server-rendered HTML before hydration, so on the dev server's first hit at
 * a route (where compiling outlasts any timeout) the click lands on a form with
 * no handler and silently does nothing. That produced a whole run of bogus
 * login timeouts. A real check waits for the handler itself.
 */
async function waitForSubmitHandler(page) {
  await page.waitForFunction(() => {
    const form = document.querySelector('form');
    if (!form) return false;
    const key = Object.keys(form).find((k) => k.startsWith('__reactProps$'));
    return !!key && typeof form[key].onSubmit === 'function';
  }, { timeout: 120000 });
}

/** Which account the current session actually belongs to, per the backend. */
async function sessionEmail(page) {
  return page.evaluate(async () => {
    const token = localStorage.getItem('token');
    if (!token) return null;
    const res = await fetch('/api/v1/users/me', {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) return null;
    return (await res.json()).email;
  });
}

/**
 * Sign in as a specific user, from a clean session.
 *
 * Storage must be cleared first: /login redirects to the dashboard the instant a
 * token exists, so "log in as somebody else" silently keeps the previous user.
 * That produced a run whose courier2 assertions were really reading courier1's
 * numbers. The session is then confirmed against the backend rather than assumed.
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
  if (who !== email) throw new Error(`session belongs to ${who}, expected ${email}`);
}

const API = 'http://localhost:8080';

/**
 * The backend's own numbers for a courier, used as the expected values.
 *
 * This script used to assert hardcoded figures ("5.0", "2 of 3", "1 review").
 * That was a trap: the numbers were true of one snapshot of the demo data, so
 * the suite started failing the moment anybody created or deleted a delivery —
 * and a suite that cries wolf gets ignored, which is worse than no suite. The
 * invariant worth protecting is not any particular score, it is that the
 * courier's own panel and the admin's table are computed the same way from the
 * same data. So the expectation is read from the API and both views are checked
 * against it.
 */
async function apiStats(email) {
  const login = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'password123' }),
  });
  const { token } = await login.json();
  const res = await fetch(`${API}/api/v1/courier/deliveries/stats`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) return null;
  return res.json();
}

/** The same courier's row in the admin users list, read from the API. */
async function apiAdminRow(email) {
  const login = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@swift.com', password: 'password123' }),
  });
  const { token } = await login.json();
  const res = await fetch(`${API}/api/v1/admin/users`, { headers: { Authorization: `Bearer ${token}` } });
  const users = await res.json();
  return users.find((u) => u.email === email) || null;
}

/**
 * Read the courier stats strip as a label -> value map.
 *
 * Parsed from the DOM rather than by regexing innerText: `stat-label` is
 * uppercased in CSS, so innerText yields "COMPLETED" and every case-sensitive
 * assertion against it fails for the wrong reason.
 */
async function readStats(page) {
  await page.waitForSelector('[aria-label="Your track record"]', { timeout: 60000 });
  // Wait for the skeleton to be replaced by real numbers; a strip still showing
  // pulses means the stats request has not landed and any value read is fiction.
  await page.waitForFunction(() => {
    const strip = document.querySelector('[aria-label="Your track record"]');
    return strip && !strip.querySelector('.animate-pulse');
  }, { timeout: 60000 });

  return page.evaluate(() => {
    const strip = document.querySelector('[aria-label="Your track record"]');
    const out = { text: strip.innerText };
    Array.from(strip.querySelectorAll('div')).forEach((cell) => {
      const label = cell.querySelector(':scope > p.stat-label');
      const value = cell.querySelector(':scope > p.stat-value');
      if (label && value) out[label.innerText.trim().toLowerCase()] = value.innerText.trim();
    });
    return out;
  });
}

(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 140)));

  try {
    // ---- courier1: has a real track record ----
    const api1 = await apiStats('courier1@swift.com');
    const admin1 = await apiAdminRow('courier1@swift.com');
    if (!api1) throw new Error('could not read courier1 stats from the API');
    console.log(`  --- API says: total=${api1.totalDeliveries} done=${api1.completedDeliveries} ` +
      `active=${api1.activeDeliveries} cancelled=${api1.cancelledDeliveries} ` +
      `rating=${api1.averageRating} (${api1.ratingCount} reviews) ---`);

    await login(page, 'courier1@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const s1 = await readStats(page);
    console.log('  --- courier1 strip as rendered ---');
    console.log('  ' + s1.text.replace(/\n+/g, ' | '));

    ok('track record panel renders for a courier', /average rating/i.test(s1.text));
    ok('Completed counter matches the backend', s1.completed === String(api1.completedDeliveries),
      `ui "${s1.completed}" vs api ${api1.completedDeliveries}`);
    ok('In progress counter matches the backend', s1['in progress'] === String(api1.activeDeliveries),
      `ui "${s1['in progress']}" vs api ${api1.activeDeliveries}`);
    ok('Cancelled counter matches the backend', s1.cancelled === String(api1.cancelledDeliveries),
      `ui "${s1.cancelled}" vs api ${api1.cancelledDeliveries}`);

    // The exact regression this feature shipped with: unrated orders were
    // averaged in as zeros, so a single 5-star review read 1.7. The check is
    // that the panel shows the API's average and does not show the mean over
    // ALL assigned deliveries -- which is only a different number when the
    // courier has unrated orders, so skip it when there are none.
    const wanted = api1.averageRating == null ? null : Number(api1.averageRating).toFixed(1);
    if (wanted !== null) {
      ok('average rating matches the backend', new RegExp(`(^|\\s)${wanted}(\\s|$)`).test(s1.text),
        `ui should show ${wanted}; got "${s1.text.replace(/\n+/g, ' | ')}"`);
      if (api1.ratingCount < api1.totalDeliveries) {
        // What the bug would show: every unrated order counted as a zero, i.e.
        // the sum of the ratings divided by ALL assigned deliveries. The sum is
        // recoverable from the API as average x count.
        const dragged = (api1.averageRating * api1.ratingCount) / api1.totalDeliveries;
        ok('average is over rated deliveries only, not zero-dragged',
          !new RegExp(`(^|\\s)${dragged.toFixed(1)}(\\s|$)`).test(s1.text),
          `correct ${wanted}, zero-dragged would be ${dragged.toFixed(1)} `
          + `(${api1.ratingCount} rated / ${api1.totalDeliveries} assigned)`);
      } else {
        console.log('  (every delivery is rated, so zero-dragging cannot be detected here)');
      }
      ok('review count is shown', new RegExp(`\\((\\d+) reviews?\\)`).test(s1.text),
        `expected (${api1.ratingCount} review(s))`);
    } else {
      ok('a courier with no ratings is not given a score', !/\b0\.0\b/.test(s1.text), s1.text.replace(/\n+/g, ' | '));
    }
    ok('five stars drawn', (await page.locator('[aria-label="Your track record"] svg').count()) >= 5);

    // ---- courier2: whatever their history happens to be ----
    const api2 = await apiStats('courier2@swift.com');
    const admin2 = await apiAdminRow('courier2@swift.com');
    await login(page, 'courier2@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const s2 = await readStats(page);
    console.log('  --- courier2 strip as rendered ---');
    console.log('  ' + s2.text.replace(/\n+/g, ' | '));

    ok('courier2 completed counter matches the backend',
      s2.completed === String(api2.completedDeliveries),
      `ui "${s2.completed}" vs api ${api2.completedDeliveries}`);
    if (api2.averageRating == null) {
      ok('unrated courier is told there are no ratings', /no ratings yet|not rated/i.test(s2.text), s2.text.replace(/\n+/g, ' | '));
      ok('unrated courier is NOT given a 0.0 score', !/\b0\.0\b/.test(s2.text));
      if (api2.totalDeliveries === 0) {
        ok('no footnote for a courier with no assignments at all', !/assigned to you in total/i.test(s2.text));
      } else {
        ok('the footnote reports the real assignment total',
          new RegExp(`\\b${api2.totalDeliveries}\\s+orders?\\b`).test(s2.text),
          `expected "${api2.totalDeliveries} orders"; got "${s2.text.replace(/\n+/g, ' | ')}"`);
      }
    } else {
      ok('rated courier sees their average', new RegExp(`(^|\\s)${Number(api2.averageRating).toFixed(1)}(\\s|$)`).test(s2.text),
        s2.text.replace(/\n+/g, ' | '));
    }

    // ---- admin: rating beside each courier ----
    await login(page, 'admin@swift.com');
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('text=Total users', { timeout: 60000 });
    await page.getByRole('button', { name: /users/i }).first().click();
    await page.waitForSelector('th:has-text("Rating")', { timeout: 30000 });
    await page.waitForFunction(
      () => !/Loading/.test(document.body.innerText),
      { timeout: 30000 }
    ).catch(() => {});

    const rows = await page.evaluate(() => {
      const table = Array.from(document.querySelectorAll('table')).pop();
      return Array.from(table.querySelectorAll('tbody tr')).map((tr) =>
        Array.from(tr.querySelectorAll('td')).map((td) => td.innerText.replace(/\n+/g, ' ').trim())
      );
    });
    const head = await page.evaluate(() =>
      Array.from(document.querySelectorAll('th')).map((t) => t.innerText.trim()));
    console.log('  --- admin users table ---');
    console.log('  head: ' + head.join(' | '));
    rows.forEach((r) => console.log('  row:  ' + r.join(' | ')));

    const find = (e) => rows.find((r) => r[0] === e) || [];
    ok('a Rating column exists', head.some((h) => /rating/i.test(h)), head.join(','));
    ok('a Deliveries column exists', head.some((h) => /deliveries/i.test(h)), head.join(','));

    // The admin table must agree with the courier's own panel. This is the
    // cross-view check: when the two were computed differently, the courier
    // read 1.7 while the admin read 5.0 for the same person.
    const c1 = find('courier1@swift.com');
    ok('admin row agrees with the API on deliveries',
      new RegExp(`\\b${admin1.deliveredDeliveries}\\s*/\\s*${admin1.totalDeliveries}\\b`).test(c1[2] || ''),
      `ui "${c1[2]}" vs api ${admin1.deliveredDeliveries}/${admin1.totalDeliveries}`);
    if (admin1.averageRating == null) {
      ok('admin row shows no score for an unrated courier', /no ratings yet|not rated/i.test(c1[3] || ''), c1.join(' | '));
    } else {
      ok('admin row shows the same average the courier sees',
        new RegExp(`(^|\\s)${Number(admin1.averageRating).toFixed(1)}(\\s|$)`).test(c1[3] || ''),
        `ui "${c1[3]}" vs api ${admin1.averageRating}`);
    }
    ok('courier1 rating is labelled as coming from reviews', /review/i.test(c1[3] || ''), c1.join(' | '));
    ok('admin average and courier panel average are the same number',
      admin1.averageRating === api1.averageRating,
      `admin ${admin1.averageRating} vs stats ${api1.averageRating}`);

    const c2 = find('courier2@swift.com');
    ok('admin row agrees with the API on deliveries for courier2',
      new RegExp(`\\b${admin2.deliveredDeliveries}\\s*/\\s*${admin2.totalDeliveries}\\b`).test(c2[2] || ''),
      `ui "${c2[2]}" vs api ${admin2.deliveredDeliveries}/${admin2.totalDeliveries}`);
    if (admin2.averageRating == null) {
      ok('courier2 row (no history) shows no score', /no ratings yet|not rated/i.test(c2[3] || ''), c2.join(' | '));
    } else {
      ok('courier2 row shows their average',
        new RegExp(`(^|\\s)${Number(admin2.averageRating).toFixed(1)}(\\s|$)`).test(c2[3] || ''), c2.join(' | '));
    }

    const cl = find('client1@swift.com');
    ok('client row shows a dash, never a fake score', /—/.test(cl[2] || '') && /—/.test(cl[3] || ''), cl.join(' | '));
    ok('client row has no decimal score', !/\d\.\d/.test(cl[3] || ''), cl.join(' | '));

    // ---- phone viewport: the panel must not overflow the screen ----
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page, 'courier1@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const m = await readStats(page);
    const overflow = await page.evaluate(() => {
      const strip = document.querySelector('[aria-label="Your track record"]');
      return strip.getBoundingClientRect().width - document.documentElement.clientWidth;
    });
    ok('courier panel fits a 390px phone', overflow <= 1, `overflows by ${Math.round(overflow)}px`);
    ok('phone view shows the same rating as the API',
      api1.averageRating == null
        ? /no ratings yet|not rated/i.test(m.text)
        : new RegExp(`(^|\\s)${Number(api1.averageRating).toFixed(1)}(\\s|$)`).test(m.text),
      m.text.replace(/\n+/g, ' | '));
    ok('no horizontal page scroll on a phone',
      await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1),
      `scrollWidth=${await page.evaluate(() => document.documentElement.scrollWidth)}`);

    ok('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' || '));
  } catch (e) {
    ok('threw: ' + String(e).split('\n')[0].slice(0, 200), false);
  }

  await b.close();
  results.forEach(([s, n, e]) => console.log(`  ${s} ${n}${e ? '\n        << ' + e : ''}`));
  const ko = results.filter(([s]) => s === 'KO  ').length;
  console.log(ko === 0 ? 'ALL CHECKS OK' : `${ko} FAILURES`);
  process.exit(ko === 0 ? 0 : 1);
})();
