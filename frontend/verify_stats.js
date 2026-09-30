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
    await login(page, 'courier1@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const s1 = await readStats(page);
    console.log('  --- courier1 strip as rendered ---');
    console.log('  ' + s1.text.replace(/\n+/g, ' | '));

    ok('track record panel renders for a courier', /average rating/i.test(s1.text));
    ok('Completed counter shows 2', s1.completed === '2', `got "${s1.completed}"`);
    ok('In progress counter shows 1', s1['in progress'] === '1', `got "${s1['in progress']}"`);
    ok('Cancelled counter shows 0', s1.cancelled === '0', `got "${s1.cancelled}"`);
    // The exact regression this feature shipped with: unrated orders were
    // averaged in as zeros, turning a single 5-star review into 1.7.
    ok('average rating reads 5.0', /(^|\s)5\.0(\s|$)/.test(s1.text), s1.text.replace(/\n+/g, ' | '));
    ok('average rating does NOT read the zero-dragged 1.7', !/1\.7/.test(s1.text));
    ok('review count shown as "(1 review)"', /\(1 review\)/.test(s1.text));
    ok('five stars drawn', (await page.locator('[aria-label="Your track record"] svg').count()) >= 5);

    // ---- courier2: no history at all ----
    await login(page, 'courier2@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    const s2 = await readStats(page);
    console.log('  --- courier2 strip as rendered ---');
    console.log('  ' + s2.text.replace(/\n+/g, ' | '));

    ok('unrated courier is told there are no ratings', /no ratings yet|not rated/i.test(s2.text), s2.text.replace(/\n+/g, ' | '));
    ok('unrated courier is NOT given a 0.0 score', !/\b0\.0\b/.test(s2.text));
    ok('unrated courier still sees zero counters', s2.completed === '0' && s2.cancelled === '0',
      `completed="${s2.completed}" cancelled="${s2.cancelled}"`);
    ok('no footnote for a courier with no assignments', !/assigned to you in total/i.test(s2.text));

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

    const c1 = find('courier1@swift.com');
    ok('courier1 row shows 2 of 3 delivered', /\b2\s*\/\s*3\b/.test(c1[2] || ''), c1.join(' | '));
    ok('courier1 row shows rating 5.0', /5\.0/.test(c1[3] || ''), c1.join(' | '));
    ok('courier1 row does NOT show the wrong 1.7', !/1\.7/.test(c1[3] || ''), c1.join(' | '));
    ok('courier1 rating is labelled as coming from reviews', /review/i.test(c1[3] || ''), c1.join(' | '));

    const c2 = find('courier2@swift.com');
    ok('courier2 row (no history) shows no score', /no ratings yet|not rated/i.test(c2[3] || ''), c2.join(' | '));
    ok('courier2 row shows 0 of 0 delivered', /\b0\s*\/\s*0\b/.test(c2[2] || ''), c2.join(' | '));

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
    ok('phone view still shows the rating', /5\.0/.test(m.text), m.text.replace(/\n+/g, ' | '));
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
