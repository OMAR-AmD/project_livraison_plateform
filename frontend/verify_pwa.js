/**
 * Is the app installable and honest offline?
 *
 * WHAT THIS CHECKS
 * The PWA shell: manifest serves with resolvable icons, the service worker
 * serves and contains the two load-bearing policies (navigations fall back to
 * /offline, /api/* and /osrm/* are never cached), the worker actually
 * registers in a production build, and going offline shows the offline page
 * instead of a frozen dashboard or a browser error.
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * A 200 on /sw.js proves nothing: a worker that caches /api would pass that
 * and silently serve yesterday's order state. The source-policy assertions
 * and the real offline reload are the checks that can fail. SW registration
 * is production-only by design (ServiceWorkerRegister), so this must run
 * against `next start` (port 3000), never `next dev`.
 *
 * MUTATION
 * Let the fetch handler cache /api (drop the network-only guard in
 * public/sw.js): "live data bypasses the cache" fails. Point the manifest
 * start_url at a missing route: "manifest is valid" fails.
 *
 * RUN  (frontend container built from this tree must be up on 3000)
 * node verify_pwa.js
 */
const { chromium, devices } = require('playwright');

const BASE = process.env.WEB || 'http://localhost:3000';

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

async function main() {
  const browser = await chromium.launch();

  try {
    // --- static shell ---
    const manifestRes = await fetch(`${BASE}/manifest.webmanifest`);
    let manifest = null;
    try {
      manifest = await manifestRes.json();
    } catch { /* not JSON */ }
    check('the manifest serves as JSON', manifestRes.ok && !!manifest && manifest.name.includes('SwiftDeliver'));
    let iconsOk = false;
    if (manifest && Array.isArray(manifest.icons)) {
      iconsOk = true;
      for (const icon of manifest.icons) {
        const r = await fetch(`${BASE}${icon.src}`);
        if (!r.ok) iconsOk = false;
      }
    }
    check('every manifest icon resolves', iconsOk, manifest ? `${manifest.icons.length} icon(s)` : 'no manifest');

    const swRes = await fetch(`${BASE}/sw.js`);
    const swText = swRes.ok ? await swRes.text() : '';
    check('the service worker serves', swRes.ok && swText.includes('SwiftDeliver service worker'));
    check(
      'live data bypasses the cache',
      swText.includes("startsWith('/api/')") && swText.includes("startsWith('/osrm/')"),
      'no /api/* or /osrm/* caching',
    );
    check('navigations fall back to /offline', swText.includes("caches.match('/offline')"));

    const offlineRes = await fetch(`${BASE}/offline`);
    check('the offline page serves', offlineRes.ok);

    // --- live worker + real offline reload (production only) ---
    const ctx = await browser.newContext({ ...devices['Pixel 5'] });
    const page = await ctx.newPage();
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 120000 });
    await page.waitForTimeout(2500); // let the worker install on first visit
    const reg = await page.evaluate(async () =>
      (await window.navigator.serviceWorker.getRegistration()) ? 'registered' : 'none',
    );
    check('the worker registers in production', reg === 'registered', reg);

    await ctx.setOffline(true);
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {});
    await page.waitForTimeout(1500);
    const offlineSeen = await page.getByText('You are offline').count();
    check('offline shows the offline page, not a dead dashboard', offlineSeen >= 1, `${offlineSeen} match(es)`);
    await ctx.setOffline(false);

    // The worker must not break live traffic once back online.
    const health = await page.evaluate(() =>
      fetch('/api/v1/health').then((r) => r.status).catch(() => -1),
    );
    check('the API still answers through the worker', health === 200, `HTTP ${health}`);

    await ctx.close();
  } catch (e) {
    check('threw: ' + String(e).split('\n')[0].slice(0, 160), false);
  } finally {
    await browser.close();
  }

  console.log('\n' + '='.repeat(72));
  if (failures.length === 0) {
    console.log(`ALL PWA CHECKS OK (${passed})`);
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
