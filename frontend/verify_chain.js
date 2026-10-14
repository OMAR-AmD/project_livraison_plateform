/**
 * Is the seal chain real on the running platform?
 *
 * WHAT THIS CHECKS
 * The unit tests prove the chain maths in isolation. This proves the product,
 * end to end against a live backend and database:
 *   1. two sealed deliveries link (B.prev == A.hash, A.prev == GENESIS),
 *   2. two deliveries sealed *concurrently* still line up (the head lock),
 *   3. deleting a sealed delivery through the API leaves a signed tombstone
 *      and the chain stays green,
 *   4. deleting a sealed row behind the service (SQL) breaks the chain at
 *      the successor, pointing at exactly the row that no longer follows,
 *   5. deleting the newest row leaves a valid shorter chain -- the known gap
 *      December's chain-head anchor closes, stated here rather than hidden.
 *
 * RUN
 * node verify_chain.js
 * (needs the backend on localhost:8080 with the demo profile seeded)
 */

const { execSync } = require('node:child_process');

const BASE = 'http://localhost:8080/api/v1';
const PASSWORD = 'password123';
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
      description: `chain probe ${label}`,
      pickupAddress: 'probe pickup',
      pickupLat: DROP_LAT,
      pickupLng: DROP_LNG,
      dropoffAddress: 'probe dropoff',
      dropoffLat: DROP_LAT,
      dropoffLng: DROP_LNG,
    }),
  });
  if (!r.ok) throw new Error(`could not create probe delivery: ${r.status} ${JSON.stringify(r.body)}`);
  return r.body.id;
}

async function readyDelivery(admin, courier, courierId, client, label) {
  const id = await createDelivery(client, label);
  await api(`/admin/deliveries/${id}/assign`, admin, {
    method: 'PATCH',
    body: JSON.stringify({ courierId }),
  });
  await api(`/courier/deliveries/${id}/status`, courier, {
    method: 'PATCH',
    body: JSON.stringify({ status: 'IN_TRANSIT' }),
  });
  return id;
}

async function sealAtDoor(courier, id) {
  return api(`/courier/deliveries/${id}/status`, courier, {
    method: 'PATCH',
    body: JSON.stringify({ status: 'DELIVERED', latitude: DROP_LAT, longitude: DROP_LNG }),
  });
}

/** Removes a row behind the service's back: no tombstone, no key, just SQL. */
function sqlDelete(id) {
  execSync(
    `docker exec delivery_postgres_demo psql -U admin -d delivery_db -c "DELETE FROM deliveries WHERE id = '${id}'"`,
    { stdio: 'pipe' },
  );
}

/** Exact chain rows, so every step of the demo is auditable. */
function dumpRows(label) {
  const out = execSync(
    `docker exec delivery_postgres_demo psql -U admin -d delivery_db -t -c "SELECT left(id::text,8), seal_seq, left(prev_hash,8), left(proof_hash,8) FROM deliveries ORDER BY seal_seq"`,
    { encoding: 'utf8' },
  );
  const tombs = execSync(
    `docker exec delivery_postgres_demo psql -U admin -d delivery_db -t -c "SELECT left(delivery_id::text,8), seal_seq FROM seal_tombstone ORDER BY seal_seq"`,
    { encoding: 'utf8' },
  );
  console.log(`   rows ${label}: deliveries[${out.trim().replace(/\s+/g, ' ')}] tombs[${tombs.trim().replace(/\s+/g, ' ')}]`);
}

async function main() {
  console.log('='.repeat(72));
  console.log('Live seal-chain check (real API, real database)');
  console.log('='.repeat(72));

  const admin = await auth('admin@swift.com');
  const client1 = await auth('client1@swift.com');
  const courier1 = await auth('courier1@swift.com');

  const couriers = (await api('/admin/users/couriers', admin)).body;
  const list = couriers.content || couriers;
  const c1 = list.find((u) => u.email === 'courier1@swift.com');

  // ============ 1+2: seal two, read the links ============
  console.log('\n-- sealing A then B --');
  const a = await readyDelivery(admin, courier1, c1.id, client1, 'A');
  const b = await readyDelivery(admin, courier1, c1.id, client1, 'B');
  const sealedA = await sealAtDoor(courier1, a);
  const sealedB = await sealAtDoor(courier1, b);
  check('both seals accepted', sealedA.ok && sealedB.ok,
    `${sealedA.status}/${sealedB.status}`);

  const proofA = (await api(`/admin/deliveries/${a}/proof`, admin)).body;
  const proofB = (await api(`/admin/deliveries/${b}/proof`, admin)).body;
  check('A starts the chain', proofA?.prevHash === 'GENESIS', String(proofA?.prevHash));
  check('B links against A', proofB?.prevHash === proofA?.proof,
    `${String(proofB?.prevHash).slice(0, 12)}... vs ${String(proofA?.proof).slice(0, 12)}...`);

  let chain = (await api('/admin/deliveries/proof-chain', admin)).body;
  console.log('   chain:', JSON.stringify(chain));
  check('two seals verify as a chain', chain?.ok === true && chain?.sealed === 2
    && chain?.tombstones === 0, JSON.stringify(chain));

  // ============ 3: concurrent seals line up ============
  console.log('\n-- sealing C and D concurrently --');
  const c = await readyDelivery(admin, courier1, c1.id, client1, 'C');
  const d = await readyDelivery(admin, courier1, c1.id, client1, 'D');
  const [sealedC, sealedD] = await Promise.all([sealAtDoor(courier1, c), sealAtDoor(courier1, d)]);
  check('both concurrent seals accepted (no fork, no 500)',
    sealedC.ok && sealedD.ok, `${sealedC.status}/${sealedD.status}`);
  dumpRows('after parallel seals');

  // The lock decides the commit order, not creation order: the newer seal is
  // whichever one's link points at the other's proof. The deletes below must
  // follow the actual order — deleting "C" blindly could hit the tail and
  // prove nothing about middle deletion.
  const proofC = (await api(`/admin/deliveries/${c}/proof`, admin)).body;
  const proofD = (await api(`/admin/deliveries/${d}/proof`, admin)).body;
  const cIsOlder = proofC?.prevHash === proofB?.proof && proofD?.prevHash === proofC?.proof;
  const dIsOlder = proofD?.prevHash === proofB?.proof && proofC?.prevHash === proofD?.proof;
  check('the parallel seals committed in a definite order', cIsOlder || dIsOlder,
    `C.prev ${String(proofC?.prevHash).slice(0, 8)} D.prev ${String(proofD?.prevHash).slice(0, 8)}`);
  const older = cIsOlder ? c : d;
  const newer = cIsOlder ? d : c;
  console.log(`   commit order: ...${String(older).slice(0, 8)} then ...${String(newer).slice(0, 8)}`);

  chain = (await api('/admin/deliveries/proof-chain', admin)).body;
  console.log('   chain:', JSON.stringify(chain));
  check('concurrent seals form one line, not a fork',
    chain?.ok === true && chain?.sealed === 4, JSON.stringify(chain));

  // ============ 4: API delete leaves a tombstone, chain stays green ============
  console.log('\n-- deleting B through the API --');
  const del = await api(`/admin/deliveries/${b}`, admin, { method: 'DELETE' });
  check('the API delete succeeds', del.status === 204 || del.ok, `status ${del.status}`);
  dumpRows('after API delete of B');

  const gone = await api(`/admin/deliveries/${b}/proof`, admin);
  check('the deleted delivery no longer has a readable proof',
    gone.status === 409 || (gone.status === 400 && /not found/i.test(JSON.stringify(gone.body))),
    `status ${gone.status}`);

  chain = (await api('/admin/deliveries/proof-chain', admin)).body;
  console.log('   chain:', JSON.stringify(chain));
  check('the recorded deletion keeps the chain green',
    chain?.ok === true && chain?.sealed === 4 && chain?.tombstones === 1,
    JSON.stringify(chain));

  // ============ 5: SQL delete of the middle row breaks at the successor ============
  // `older` is a true middle row (A, tomb B, older, newer follow it), so this
  // is a deletion the chain must catch — unlike deleting the tail.
  console.log(`\n-- deleting ...${String(older).slice(0, 8)} (a middle row) behind the service (SQL) --`);
  sqlDelete(older);
  dumpRows('after SQL delete of the middle row');

  chain = (await api('/admin/deliveries/proof-chain', admin)).body;
  console.log('   chain:', JSON.stringify(chain));
  check('a row removed with no tombstone breaks the chain',
    chain?.ok === false, JSON.stringify(chain));
  check('the break points at the successor whose link dangles',
    chain?.brokenAt === newer, `brokenAt ${chain?.brokenAt}, expected ${newer}`);

  // ============ 6: SQL delete of the newest row: the stated tail gap ============
  console.log(`\n-- deleting ...${String(newer).slice(0, 8)} (the newest) behind the service (SQL) --`);
  sqlDelete(newer);

  chain = (await api('/admin/deliveries/proof-chain', admin)).body;
  console.log('   chain:', JSON.stringify(chain));
  check('a shorter chain still verifies: tail deletion is the known gap the head anchor will close',
    chain?.ok === true && chain?.sealed === 2 && chain?.tombstones === 1,
    JSON.stringify(chain));

  console.log('\n' + '='.repeat(72));
  console.log(`${passed} passed, ${failures.length} failed`);
  if (failures.length > 0) {
    console.log('failures:\n - ' + failures.join('\n - '));
    process.exitCode = 1;
  }
}

main().catch((e) => {
  console.error('FATAL', e);
  process.exitCode = 1;
});
