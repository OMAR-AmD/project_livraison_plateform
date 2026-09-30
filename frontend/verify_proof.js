/**
 * Is the proof of delivery real on the running platform?
 *
 * WHAT THIS CHECKS
 * The unit tests prove the seal's maths in isolation. This proves the product:
 * that a courier who is not at the drop-off is REFUSED, that a courier at the
 * drop-off is SEALED, that the seal survives a round trip through Postgres and
 * still verifies, and that two deliveries at the same place and time do not
 * share a seal.
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * A "verified" endpoint that always answers true would pass a one-sided test.
 * This script therefore asserts BOTH directions on the same running service:
 * the far-away delivery must be rejected and the at-the-door delivery must be
 * accepted. A gate stuck open fails the first; a gate stuck shut fails the
 * second. Neither can pass by accident.
 *
 * WHY THE HAPPY PATH USES THE REDIS FALLBACK ON PURPOSE
 * The courier UI never sends coordinates -- it calls the status endpoint with
 * `{status}` alone. So the path that actually runs in the demo is "fall back to
 * the last broadcast position", and that is what is exercised here. The write-up
 * path (coordinates in the request body) is exercised separately.
 *
 * RUN
 * node verify_proof.js
 */

const BASE = 'http://localhost:8080/api/v1';
const PASSWORD = 'password123';

// The same Casablanca point the fraud probe uses, so the two scripts agree about
// where "the drop-off" is.
const DROP_LAT = 33.5891;
const DROP_LNG = -7.6311;

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

/** Bare headers, never {headers}. See verify_fraud_live.js for why. */
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

const metresNorth = (m) => m / 111320;

async function api(path, headers, options = {}) {
  const r = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { ...headers, ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  const body = r.status === 204 ? null : await r.json().catch(() => null);
  return { status: r.status, ok: r.ok, body };
}

async function createDelivery(client, label) {
  const r = await api('/client/deliveries', client, {
    method: 'POST',
    body: JSON.stringify({
      description: `proof probe ${label}`,
      pickupAddress: 'probe pickup',
      pickupLat: DROP_LAT,
      pickupLng: DROP_LNG,
      dropoffAddress: 'probe dropoff',
      dropoffLat: DROP_LAT,
      dropoffLng: DROP_LNG,
    }),
  });
  if (!r.ok) throw new Error(`could not create probe delivery: ${r.status}`);
  return r.body.id;
}

async function assign(admin, deliveryId, courierId) {
  return api(`/admin/deliveries/${deliveryId}/assign`, admin, {
    method: 'PATCH',
    body: JSON.stringify({ courierId }),
  });
}

async function setStatus(headers, deliveryId, status, coords) {
  return api(`/courier/deliveries/${deliveryId}/status`, headers, {
    method: 'PATCH',
    body: JSON.stringify(coords ? { status, ...coords } : { status }),
  });
}

async function main() {
  console.log('='.repeat(72));
  console.log('Live proof-of-delivery check (real API, real database)');
  console.log('='.repeat(72));

  const admin = await auth('admin@swift.com');
  const client1 = await auth('client1@swift.com');
  const client2 = await auth('client2@swift.com');
  const courier1 = await auth('courier1@swift.com');
  const courier2 = await auth('courier2@swift.com');

  const couriers = (await api('/admin/users/couriers', admin)).body;
  const list = couriers.content || couriers;
  const c1 = list.find((u) => u.email === 'courier1@swift.com');
  const c2 = list.find((u) => u.email === 'courier2@swift.com');

  const made = [];

  try {
    // ============ A: a courier claiming delivery from 3 km away ============
    console.log('\n-- delivery A: claiming delivery from 3 km away --');
    const a = await createDelivery(client1, 'A far');
    made.push(a);
    await assign(admin, a, c1.id);
    await setStatus(courier1, a, 'IN_TRANSIT');

    const far = await setStatus(courier1, a, 'DELIVERED', {
      latitude: DROP_LAT + metresNorth(3000),
      longitude: DROP_LNG,
    });
    check('a delivery claimed 3 km from the drop-off is refused',
      far.status === 400,
      `status ${far.status}`);
    check('the refusal says how far away the courier was',
      typeof far.body?.message === 'string'
        && /m from the destination/.test(far.body.message)
        && /limit 500 m/.test(far.body.message),
      far.body?.message || JSON.stringify(far.body));

    // The refusal must not have half-applied: a refused delivery that flipped to
    // DELIVERED anyway would be worse than no check at all.
    const afterRefusal = (await api('/client/deliveries', client1)).body
      .find((d) => d.id === a);
    check('the refused delivery did not change status',
      afterRefusal.status !== 'DELIVERED',
      `status is ${afterRefusal.status}`);
    check('the refused delivery carries no proof',
      afterRefusal.proofHash == null,
      `proofHash ${afterRefusal.proofHash}`);

    const proofBefore = await api(`/admin/deliveries/${a}/proof`, admin);
    check('asking for the proof of an unsealed delivery answers 409',
      proofBefore.status === 409,
      `status ${proofBefore.status}`);

    // ============ A again: the same courier, now at the door ============
    // Exactly the path the UI takes: broadcast a position, then confirm delivery
    // WITHOUT coordinates in the request. The backend must fall back to the last
    // broadcast.
    console.log('\n-- delivery A again: same courier, now at the drop-off --');
    const broadcast = await api(`/courier/deliveries/${a}/location`, courier1, {
      method: 'PATCH',
      body: JSON.stringify({ latitude: DROP_LAT, longitude: DROP_LNG }),
    });
    check('the courier broadcasts a fix at the drop-off',
      broadcast.status === 200 || broadcast.status === 204,
      `status ${broadcast.status}`);

    const sealed = await setStatus(courier1, a, 'DELIVERED');
    check('delivery from the drop-off is accepted with no coordinates in the request',
      sealed.ok, `status ${sealed.status} ${sealed.body?.message || ''}`);

    const proofHash = sealed.body?.proofHash;
    check('the response carries a 64-character HMAC seal',
      typeof proofHash === 'string' && /^[0-9a-f]{64}$/.test(proofHash),
      proofHash ? proofHash.slice(0, 16) + '...' : String(proofHash));

    check('the response records where and when',
      sealed.body?.deliveredAt != null && sealed.body?.deliveredLat != null
        && sealed.body?.deliveredLng != null,
      `at ${sealed.body?.deliveredAt} (${sealed.body?.deliveredLat}, ${sealed.body?.deliveredLng})`);

    check('the recorded distance to the drop-off is about zero',
      typeof sealed.body?.proofDistanceM === 'number' && sealed.body.proofDistanceM < 1,
      `${sealed.body?.proofDistanceM} m`);

    // The precision bug: nanos in the clock, micros in the column. If the sealed
    // timestamp kept sub-microsecond digits, re-deriving from the stored row
    // would produce a different payload and verification below would fail.
    const fraction = String(sealed.body?.deliveredAt).match(/\.(\d+)/);
    check('the sealed timestamp survived the database round trip at storeable precision',
      !fraction || fraction[1].length <= 6,
      fraction ? `.${fraction[1]}` : 'no fractional part');

    // ============ the seal still verifies once read back ============
    console.log('\n-- re-verifying A from the database --');
    const proof = await api(`/admin/deliveries/${a}/proof`, admin);
    check('the proof endpoint answers 200 for a sealed delivery',
      proof.status === 200, `status ${proof.status}`);
    check('the seal re-derived from stored fields still matches',
      proof.body?.verified === true && proof.body?.verifiable === true,
      JSON.stringify(proof.body));

    // A seal recomputed per request from mutable fields would defeat the point,
    // so the value must be the stored one, not a fresh one.
    const reread = (await api('/client/deliveries', client1)).body.find((d) => d.id === a);
    check('the seal is persisted, not recomputed on each read',
      reread.proofHash === proofHash,
      `${String(reread.proofHash).slice(0, 16)}... vs ${String(proofHash).slice(0, 16)}...`);

    // ============ B: no position at all ============
    console.log('\n-- delivery B: no position ever broadcast --');
    const b = await createDelivery(client1, 'B blind');
    made.push(b);
    await assign(admin, b, c1.id);
    await setStatus(courier1, b, 'IN_TRANSIT');

    const blind = await setStatus(courier1, b, 'DELIVERED');
    check('confirming delivery with no known position is refused',
      blind.status === 400,
      `status ${blind.status}`);
    check('the refusal names the missing position',
      /Live GPS position required/.test(blind.body?.message || ''),
      blind.body?.message || JSON.stringify(blind.body));

    // ============ D: same place, same instant, different seal ============
    console.log('\n-- delivery D: a second delivery at the same spot --');
    const d = await createDelivery(client1, 'D twin');
    made.push(d);
    await assign(admin, d, c1.id);
    await setStatus(courier1, d, 'IN_TRANSIT');
    const sealedD = await setStatus(courier1, d, 'DELIVERED', {
      latitude: DROP_LAT, longitude: DROP_LNG,
    });
    check('a second delivery from the same position is also sealed',
      sealedD.ok, `status ${sealedD.status}`);
    check('the two seals differ, so a proof cannot be transplanted',
      sealedD.body?.proofHash && sealedD.body.proofHash !== proofHash,
      sealedD.body?.proofHash ? sealedD.body.proofHash.slice(0, 16) + '...' : 'none');

    // ============ who may seal, and who may look ============
    console.log('\n-- access control --');
    const wrongCourier = await setStatus(courier2, d, 'DELIVERED', {
      latitude: DROP_LAT, longitude: DROP_LNG,
    });
    check('a courier not assigned to the delivery cannot seal it',
      wrongCourier.status === 400 || wrongCourier.status === 403,
      `status ${wrongCourier.status} ${wrongCourier.body?.message || ''}`);

    const stolen = await api(`/client/deliveries/${d}/location`, client2);
    check('another client cannot track a delivery by its id (IDOR)',
      !stolen.ok && (stolen.status === 400 || stolen.status === 403 || stolen.status === 404),
      `status ${stolen.status} ${stolen.body?.message || ''}`);
    check('the IDOR refusal does not reveal that the delivery exists',
      stolen.status === 404
        || /Delivery not found/.test(stolen.body?.message || ''),
      stolen.body?.message || `status ${stolen.status}`);

    const ownLocation = await api(`/client/deliveries/${d}/location`, client1);
    check('the owning client can still track their own delivery',
      ownLocation.status !== 400 && ownLocation.status !== 403,
      `status ${ownLocation.status}`);

    const otherProof = await api(`/admin/deliveries/${d}/proof`, client1);
    check('a client cannot read the verification endpoint (admin only)',
      otherProof.status === 403,
      `status ${otherProof.status}`);
  } finally {
    // Probe orders are deleted so repeated runs do not litter the demo data the
    // teacher will look at, and so a sealed probe order does not sit in the
    // dataset as a fake delivery.
    console.log('\n-- cleanup --');
    for (const id of made) {
      const r = await api(`/admin/deliveries/${id}`, admin, { method: 'DELETE' });
      console.log(`   ${id} deleted: ${r.status === 204 || r.ok} (${r.status})`);
    }
  }

  console.log('\n' + '='.repeat(72));
  if (failures.length === 0) {
    console.log(`ALL CHECKS OK (${passed})`);
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
