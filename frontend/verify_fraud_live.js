/**
 * Does the fraud detection fire on the running platform?
 *
 * WHAT THIS CHECKS
 * The Java unit tests prove the model and the service agree with Python. This
 * proves the wiring: that a real courier's position updates reach the detector,
 * that a physically impossible trajectory raises an alert, and that an honest
 * trajectory does not. A detector nothing calls would pass every unit test and
 * protect nobody.
 *
 * HOW THE SCENARIO IS BUILT
 * A real delivery has to be IN_TRANSIT for location updates to be accepted, so
 * the script creates one, then walks the courier along the delivery. The honest
 * pass closes on the drop-off. The fraudulent pass stalls and then reports a
 * position from across the city.
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * It asserts the two verdicts DIFFER on the same delivery, in the same session,
 * under the same model. A detector stuck at 0 fails the fraud assertion; one
 * stuck at 1 fails the honest assertion. Neither can pass by accident.
 *
 * RUN
 * node verify_fraud_live.js
 */

const BASE = 'http://localhost:8080/api/v1';
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

/** auth() returns bare headers; wrapping them in {headers} sends a literal
 *  header named "headers" and every call comes back 403 looking like a
 *  permission problem. */
async function auth(email) {
  const r = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: PASSWORD }),
  });
  if (!r.ok) throw new Error(`login failed for ${email}: ${r.status}`);
  const { token } = await r.json();
  return { Authorization: `Bearer ${token}` };
}

function metresNorth(m) {
  return m / 111320;
}
function metresEast(lat, m) {
  return m / (111320 * Math.cos((lat * Math.PI) / 180));
}

/**
 * The service derives speed from the time BETWEEN fixes. An earlier version of
 * this script slept 120 ms between broadcasts, so the courier appeared to cover
 * 120 m in 0.12 s -- 1000 km/h, impossible -- and the detector correctly flagged
 * the honest approach. The model was right and the test was wrong.
 *
 * Production cadence is 10 s, so the honest pass sleeps for real. 25 fixes is
 * about four minutes of driving, which is a reasonable price for a check that
 * exercises the real timing path rather than a faked one.
 */
const FIX_INTERVAL_MS = 10000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const TARGET_LAT = 33.5891;
const TARGET_LNG = -7.6311;

async function broadcast(headers, deliveryId, lat, lng) {
  const r = await fetch(`${BASE}/courier/deliveries/${deliveryId}/location`, {
    method: 'PATCH',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ latitude: lat, longitude: lng }),
  });
  return { status: r.status, body: r.status === 204 ? null : await r.json().catch(() => null) };
}

async function main() {
  console.log('='.repeat(72));
  console.log('Live fraud detection check (real API, real model)');
  console.log('='.repeat(72));

  const courier = await auth('courier1@swift.com');
  const client = await auth('client1@swift.com');

  // --- create a delivery whose drop-off is the training target -------------
  const createRes = await fetch(`${BASE}/client/deliveries`, {
    method: 'POST',
    headers: { ...client, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description: 'Fraud detection probe',
      pickupAddress: 'probe pickup',
      pickupLat: TARGET_LAT,
      pickupLng: TARGET_LNG,
      dropoffAddress: 'probe dropoff',
      dropoffLat: TARGET_LAT,
      dropoffLng: TARGET_LNG,
    }),
  });
  if (!createRes.ok) {
    const t = await createRes.text();
    console.log(`KO   could not create the probe delivery: ${createRes.status} ${t.slice(0, 200)}`);
    process.exit(1);
  }
  const created = await createRes.json();
  const deliveryId = created.id;
  check('probe delivery created', !!deliveryId, deliveryId);

  // --- assign it to courier1 so IN_TRANSIT is reachable -------------------
  const admin = await auth('admin@swift.com');
  const assign = await fetch(`${BASE}/admin/deliveries/${deliveryId}/assign`, {
    method: 'PATCH',
    headers: { ...admin, 'Content-Type': 'application/json' },
    body: JSON.stringify({ courierId: (await courierId()) }),
  });
  check('probe delivery assigned to courier1', assign.ok, `status ${assign.status}`);

  // --- move it to IN_TRANSIT ---------------------------------------------
  // There is no /start endpoint: the status is set through
  // PATCH /courier/deliveries/{id}/status with the enum name.
  const start = await fetch(`${BASE}/courier/deliveries/${deliveryId}/status`, {
    method: 'PATCH',
    headers: { ...courier, 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'IN_TRANSIT' }),
  });
  check('delivery is IN_TRANSIT', start.ok,
    start.ok ? '' : `status ${start.status} ${JSON.stringify(await start.json().catch(() => null))}`);

  // ======================= PASS 1: honest approach ========================
  console.log('\n-- pass 1: an honest courier driving to the drop-off --');
  console.log(`   (real 10 s cadence, ${25 * 10} s of driving)`);
  let honestFraudCount = 0;
  for (let i = 0; i < 25; i++) {
    const remaining = 3000 - i * (3000 / 25);
    const { status } = await broadcast(courier, deliveryId,
      TARGET_LAT - metresNorth(remaining), TARGET_LNG);
    if (status !== 200 && status !== 204) {
      console.log(`     (broadcast rejected: ${status})`);
      break;
    }
    await sleep(FIX_INTERVAL_MS);
  }
  const honestScores = await readScores(deliveryId);
  honestFraudCount = honestScores.filter((s) => s.fraud).length;
  const honestSpeeds = honestScores.map((s) => s.speedMps);
  check('honest approach produced no fraud verdict',
    honestFraudCount === 0,
    `${honestFraudCount} of ${honestScores.length} fixes flagged, ` +
      `speeds ${Math.min(...honestSpeeds).toFixed(1)}-${Math.max(...honestSpeeds).toFixed(1)} m/s`);

  // ======================= PASS 2: stall then vanish ======================
  console.log('\n-- pass 2: stall short of the drop-off, then report arrival from across the city --');
  for (let i = 0; i < 10; i++) {
    await broadcast(courier, deliveryId,
      TARGET_LAT - metresNorth(2000) + metresNorth(i * 0.5), TARGET_LNG);
    await sleep(FIX_INTERVAL_MS);
  }
  const vanish = await broadcast(courier, deliveryId,
    TARGET_LAT + metresNorth(900), TARGET_LNG + metresEast(TARGET_LAT, 700));

  check('the impossible jump was accepted as a position update',
    vanish.status === 200 || vanish.status === 204, `status ${vanish.status}`);

  // Read the trail AFTER the jump. An earlier version sliced against a
  // length captured before the write and raced the Redis round-trip, so a
  // verdict that was demonstrably recorded came back as "none flagged".
  await sleep(500);
  const afterScores = await readScores(deliveryId);

  // The endpoint returns the trail oldest-first, which is the order a curve is
  // read in. The search below is order-independent either way.
  const afterJump = afterScores;
  const jumpScore = afterJump.find((s) => s.speedMps > 200);
  check('the impossible jump raised a fraud verdict',
    !!jumpScore && jumpScore.fraud === true,
    jumpScore
      ? `speed ${jumpScore.speedMps} m/s, score ${jumpScore.score}, flagged=${jumpScore.fraud}`
      : `no fix with an implausible speed among ${afterScores.length} recorded`);

  const newFraud = jumpScore ? [jumpScore] : [];

  check('the two passes on the same delivery disagree',
    honestFraudCount === 0 && newFraud.length > 0,
    'honest clean, fraudulent flagged');

  // ======================= cleanup ========================================
  // The probe order is deleted so repeated runs do not litter the demo data the
  // teacher will look at. Ratings are attached to deliveries, so leaving these
  // behind would quietly drag down the courier's average over time.
  const admin2 = await auth('admin@swift.com');
  const del = await fetch(`${BASE}/admin/deliveries/${deliveryId}`, {
    method: 'DELETE',
    headers: admin2,
  });
  console.log(`\n-- cleanup --\n   probe delivery deleted: ${del.ok} (${del.status})`);

  console.log('\n' + '='.repeat(72));
  if (failures.length === 0) {
    console.log(`ALL CHECKS OK (${passed})`);
  } else {
    console.log(`${failures.length} FAILURE(S):`);
    failures.forEach((f) => console.log('  - ' + f));
  }
  process.exit(failures.length === 0 ? 0 : 1);
}

/** courier1's own user id, resolved from the couriers listing. */
async function courierId() {
  const admin = await auth('admin@swift.com');
  const r = await fetch(`${BASE}/admin/users/couriers`, { headers: admin });
  const list = await r.json();
  const c = (list.content || list).find((u) => u.email === 'courier1@swift.com');
  return c.id;
}

/** Read the score trail the detector wrote for this delivery.
 *
 *  Read through the API, not `docker exec redis-cli`. The trail now has a real
 *  endpoint — the same one the admin dashboard uses — so shelling into Redis
 *  tested an internal representation instead of the product, and it would have
 *  broken silently the moment a field name changed. Which it did.
 */
async function readScores(deliveryId) {
  const admin = await auth('admin@swift.com');
  const r = await fetch(`${BASE}/admin/deliveries/${deliveryId}/fraud-trail`, { headers: admin });
  if (r.status === 409) {
    // Treating this as an empty trail would make every assertion below pass for
    // the wrong reason: "nothing flagged" and "nothing was ever recorded" look
    // identical from here.
    throw new Error(
      `the detector recorded no trail for ${deliveryId} (HTTP 409). Either no ` +
      'position update reached it, or scoring is not wired into ' +
      'DeliveryService.updateCourierLocation.'
    );
  }
  if (!r.ok) {
    throw new Error(`could not read the fraud trail: HTTP ${r.status}`);
  }
  return (await r.json()).points;
}

main().catch((e) => {
  console.error('\nscript failed:', e.message);
  process.exit(1);
});