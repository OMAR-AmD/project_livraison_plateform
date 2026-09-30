const { chromium } = require('playwright');
const BASE = 'http://localhost:3000';
const results = [];
const ok = (n, c) => { results.push([c ? 'OK  ' : 'KO  ', n]); };
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('pageerror: ' + String(e).slice(0, 120)));
  const noCrash = async (label) => {
    await page.waitForTimeout(2500);
    const t = await page.content();
    const crashed = /Server Error|Unhandled Runtime Error|Cannot find module|Cannot read properties of null/.test(t);
    ok(label + (crashed ? ' -- CRASH TEXT PRESENT' : ''), !crashed);
    return !crashed;
  };
  const login = async (email) => {
    // Clear the session first: /login redirects the moment a token exists, so
    // signing in as a second user silently kept the first one's session.
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.waitForSelector('#login-email', { timeout: 120000 });
    // Wait for the form's submit handler to be attached, not merely for the
    // button to exist: the server-rendered HTML already has an enabled button,
    // so waiting on that passes before hydration and the click does nothing.
    await page.waitForFunction(() => {
      const form = document.querySelector('form');
      if (!form) return false;
      const key = Object.keys(form).find((k) => k.startsWith('__reactProps$'));
      return !!key && typeof form[key].onSubmit === 'function';
    }, { timeout: 120000 });
    await page.fill('#login-email', email);
    await page.fill('#login-password', 'password123');
    await page.click('button[type=submit]');
    await page.waitForURL(u => !u.pathname.startsWith('/login'), { timeout: 60000 });
  };
  try {
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
    await noCrash('public /login renders');
    await page.goto(`${BASE}/register`, { waitUntil: 'domcontentloaded' });
    await noCrash('public /register renders');

    await login('admin@swift.com');
    ok('admin login', true);
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' });
    if (await noCrash('admin /dashboard renders')) {
      const stats = await page.getByText(/Total users/i).count();
      ok('dashboard shows Total users KPI', stats > 0);
    }
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    if (await noCrash('admin /dashboard/deliveries renders')) {
      await page.waitForTimeout(6000);
      const fleet = await page.getByText(/Fleet overview/i).count();
      ok('fleet overview visible', fleet > 0);
      const t2 = await page.content();
      ok('no crash after map+poll settle', !/Server Error|Unhandled Runtime Error/.test(t2));
    }
    // admin new controls: status dropdown + delete on every row
    const selects = await page.locator('select[aria-label^="Change status"]').count();
    ok(`status dropdowns present (${selects})`, selects > 0);
    const dels = await page.locator('button[aria-label="Delete delivery"]').count();
    ok(`delete buttons present (${dels})`, dels > 0);

    await login('client1@swift.com');
    ok('client login', true);
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
    await noCrash('client deliveries renders');
  } catch (e) {
    ok('threw: ' + String(e).split('\n')[0].slice(0, 160), false);
  }
  await b.close();
  results.forEach(([s, n]) => console.log(`  ${s} ${n}`));
  const ko = results.filter(([s]) => s === 'KO  ').length;
  console.log(ko === 0 ? 'ALL PAGES OK' : `${ko} FAILURES`);
  process.exit(ko === 0 ? 0 : 1);
})();
