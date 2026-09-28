/**
 * Verifies the basemap failure path.
 *
 * The bug being guarded against: with a bare <TileLayer> and no tileerror
 * handler, a failing tile server produced a blank grey rectangle with markers
 * on it and no message. This test blocks every tile request and asserts the
 * user is actually told, so the regression cannot come back silently.
 *
 * Usage: node verify_basemap.js
 *   BASE=http://localhost:3000 node verify_basemap.js
 */
const { chromium } = require('playwright');

const BASE = process.env.BASE || 'http://localhost:3000';
const TILE_HOST = 'tile.openstreetmap.org';

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  // Fail every basemap tile, exactly as a throttled or unreachable server would.
  let blockedTiles = 0;
  await page.route(`**://${TILE_HOST}/**`, (route) => {
    blockedTiles++;
    route.abort('failed');
  });

  // Log in so the admin map is reachable. Waiting on the auth response rather
  // than on navigation is deterministic; the first hit on a route can take
  // longer than any navigation timeout while the dev server compiles it.
  //
  // The settle step matters: clicking submit before React has hydrated makes
  // the browser do a native form submission, which reloads the page and never
  // issues the API call at all.
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#login-email', { timeout: 30000 });
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForFunction(
    () => {
      const b = document.querySelector('button[type=submit]');
      return b && !b.disabled;
    },
    { timeout: 30000 }
  );

  const authResponse = page.waitForResponse(
    (r) => r.url().includes('/api/v1/auth/login') && r.status() === 200,
    { timeout: 45000 }
  );
  await page.fill('#login-email', 'admin@swift.com');
  await page.fill('#login-password', 'password123');
  await page.click('button[type=submit]');
  await authResponse;
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 45000 });

  await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(6000);

  const failures = [];

  // 1. Did we actually block anything? A test that cannot fail proves nothing.
  if (blockedTiles === 0) {
    failures.push(`no tile requests were intercepted (${blockedTiles}) - the test proved nothing`);
  } else {
    console.log(`  intercepted ${blockedTiles} tile requests`);
  }

  // 2. Is the user told, rather than left looking at a blank rectangle?
  const notice = page.locator('[role=status]', { hasText: 'Basemap unavailable' });
  const noticeVisible = await notice.isVisible().catch(() => false);
  if (noticeVisible) {
    console.log('  the failure notice is visible');
  } else {
    failures.push('no "Basemap unavailable" notice appeared despite every tile failing');
  }

  // 3. Does the notice explain the situation rather than just saying "error"?
  if (noticeVisible) {
    const text = (await notice.innerText()).replace(/\s+/g, ' ').trim();
    if (/still accurate/i.test(text)) {
      console.log(`  notice text: "${text}"`);
    } else {
      failures.push(`notice does not explain that data is still accurate: "${text}"`);
    }
  }

  // 4. Is there a working retry control?
  const retry = noticeVisible ? notice.getByRole('button', { name: /retry/i }) : null;
  if (retry && (await retry.count()) > 0) {
    console.log('  a retry control is present');
    // Prove the retry actually re-requests tiles rather than being a dead button.
    const before = blockedTiles;
    await retry.first().click();
    await page.waitForTimeout(3500);
    if (blockedTiles > before) {
      console.log(`  retry re-requested tiles (${before} -> ${blockedTiles})`);
    } else {
      failures.push('clicking Retry did not re-request any tiles - the button is dead');
    }
  } else {
    failures.push('no retry control in the notice');
  }

  // 5. The fallback tile should be in use, not an empty pane.
  const fallbackUsed = await page
    .locator('img.leaflet-tile[src*="map-tile-fallback"]')
    .count();
  if (fallbackUsed > 0) {
    console.log(`  fallback tile in use on ${fallbackUsed} tile(s)`);
  } else {
    failures.push('no fallback tile was rendered - tiles are simply absent');
  }

  await page.screenshot({ path: 'verify-basemap-degraded.png' });
  console.log('  screenshot: verify-basemap-degraded.png');

  await browser.close();

  if (failures.length > 0) {
    console.error(`\nFAILED (${failures.length}):`);
    failures.forEach((f) => console.error(`  - ${f}`));
    process.exit(1);
  }
  console.log('\nBasemap failure path verified: the user is told, and retry works.');
})();
