/**
 * Does "Use my GPS location" fill the pickup address, not just move the pin?
 *
 * WHAT THIS CHECKS
 * Booking lets the client either click the map or press "Use my GPS location".
 * Only the map path used to reverse geocode, so the GPS button moved the pin
 * and left the address box empty. This drives the real UI as a phone with a
 * granted position and asserts that the pickup text becomes the reverse-
 * geocoded label (not the raw coordinates), and that the pin lands on the map.
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * The four assertions are different failures: an empty box means the wiring
 * regressed, a coordinate-shaped value means reverse geocoding was skipped, a
 * missing pin means the coordinates never reached the form, and a missing
 * reference pin means the pickup is not shown on the dropoff map. Nominatim is
 * stubbed with a fixed display_name so the result is exact and does not depend
 * on an external service being reachable; the real Nominatim path is already
 * exercised by clicking the map.
 *
 * MUTATION
 * Make the external-coordinate effect in src/components/LocationPicker.js
 * return immediately (add `return;` after the null guard). The pickup box
 * stays empty and "the GPS position fills the pickup text" fails.
 * Drop referenceLat/referenceLng from the dropoff LocationPicker in
 * ClientView.js: the "dropoff map shows the pickup" check fails.
 *
 * RUN  (the backend must be up; WEB points at a frontend built from this tree)
 * node verify_gps_pickup.js
 * WEB=http://localhost:3001 node verify_gps_pickup.js   # against `next dev`
 */

const { chromium, devices } = require('playwright');

const BASE = process.env.WEB || 'http://localhost:3000';
const PASSWORD = 'password123';

const GPS = { latitude: 33.5891, longitude: -7.6311 }; // Anfa, Casablanca
const REVERSE_NAME = 'Rue de Test, Anfa, Casablanca, Maroc, Section B, Ignored';
// formatAddress keeps the first three components.
const EXPECTED = 'Rue de Test, Anfa, Casablanca';

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

  try {
    const ctx = await browser.newContext({
      ...devices['Pixel 5'],
      permissions: ['geolocation'],
      geolocation: GPS,
    });
    const page = await ctx.newPage();

    // Deterministic reverse geocoding: this test checks the wiring, not
    // whether Nominatim happens to be up.
    await page.route('**nominatim.openstreetmap.org/reverse**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ display_name: REVERSE_NAME }),
      }),
    );

    await signIn(page, 'client1@swift.com');
    await page.goto(`${BASE}/dashboard/deliveries`, { waitUntil: 'domcontentloaded' });

    await page.getByRole('button', { name: 'New delivery' }).first().click();
    const gpsButton = page.getByRole('button', { name: /Use my GPS location/ });
    await gpsButton.waitFor({ state: 'visible', timeout: 60000 });

    const pickup = page.getByPlaceholder('Search or click the map').first();
    await pickup.waitFor({ state: 'visible', timeout: 30000 });
    check('the pickup field starts empty', (await pickup.inputValue()) === '');

    const refBefore = await page.locator('.reference-pin-icon').count();
    check('the dropoff map has no pickup reference yet', refBefore === 0, `${refBefore} reference pin(s)`);

    await gpsButton.click();

    // Reverse geocoding is a network round trip; give it room but stop early.
    let value = '';
    const deadline = Date.now() + 20000;
    while (Date.now() < deadline) {
      value = (await pickup.inputValue()).trim();
      if (value) break;
      await page.waitForTimeout(250);
    }

    check('the GPS position fills the pickup text', value.length > 0, value || 'still empty');
    check('the text is the reverse-geocoded address', value === EXPECTED, value);
    check('the text is not just the raw coordinates', value.length > 0 && !/^33\.\d+,\s*-7\.\d+$/.test(value), value);

    // The pin only renders once the coordinates reached the parent form.
    let pins = 0;
    const pinDeadline = Date.now() + 5000;
    while (Date.now() < pinDeadline) {
      pins = await page.locator('.custom-pin-icon').count();
      if (pins >= 1) break;
      await page.waitForTimeout(200);
    }
    check('the GPS position is placed on the map', pins >= 1, `${pins} pin(s)`);

    // The dropoff map must show the pickup as a reference pin.
    let references = 0;
    const refDeadline = Date.now() + 5000;
    while (Date.now() < refDeadline) {
      references = await page.locator('.reference-pin-icon').count();
      if (references >= 1) break;
      await page.waitForTimeout(200);
    }
    check('the dropoff map shows the pickup for reference', references >= 1, `${references} reference pin(s)`);

    await ctx.close();
  } catch (e) {
    check('threw: ' + String(e).split('\n')[0].slice(0, 160), false);
  } finally {
    await browser.close();
  }

  console.log('\n' + '='.repeat(72));
  if (failures.length === 0) {
    console.log(`ALL GPS PICKUP CHECKS OK (${passed})`);
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
