const BASE = 'http://localhost:8080';
const results = [];
const ok = (n, c, extra) => results.push([c ? 'OK  ' : 'KO  ', n, extra || '']);

const j = async (path, opts = {}) => {
  const res = await fetch(BASE + path, opts);
  const text = await res.text();
  let body;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  return { status: res.status, body };
};
// Bare headers, not a { headers } envelope. Returning the envelope is a trap:
// spreading it into a fetch options object sends a header literally named
// "headers", no Authorization at all, and every call comes back 403. It reads
// like a permission bug and costs an hour.
const auth = (t) => ({ 'Content-Type': 'application/json', Authorization: `Bearer ${t}` });

(async () => {
  const login = async (email) => (await j('/api/v1/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'password123' }),
  })).body.token;

  const adminT = await login('admin@swift.com');
  const c1T = await login('courier1@swift.com');
  const c2T = await login('courier2@swift.com');
  const A = auth(adminT);

  const couriers = (await j('/api/v1/admin/users/couriers', { headers: A })).body;
  const c1 = couriers.find((c) => c.email === 'courier1@swift.com');
  const c2 = couriers.find((c) => c.email === 'courier2@swift.com');
  ok('two distinct couriers resolved', c1 && c2 && c1.id !== c2.id, `${c1?.id} vs ${c2?.id}`);
  if (!c1 || !c2) { report(); return; }

  // A scratch order to move around, so the demo data is left alone.
  const clientT = await login('client1@swift.com');
  const created = (await j('/api/v1/client/deliveries', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${clientT}` },
    body: JSON.stringify({
      description: 'ASSIGN-TEST', pickupAddress: 'A, Casa', dropoffAddress: 'B, Casa',
      pickupLat: 33.5731, pickupLng: -7.5898, dropoffLat: 33.5891, dropoffLng: -7.6311,
    }),
  })).body;
  await j(`/api/v1/client/deliveries/${created.id}/pay`, { method: 'PATCH', headers: auth(clientT) });
  console.log(`  scratch order ${created.id.slice(0, 8)}`);

  const assign = (id, courierId) =>
    j(`/api/v1/admin/deliveries/${id}/assign`, { method: 'PATCH', headers: A, body: JSON.stringify({ courierId }) });
  const unassign = (id) => j(`/api/v1/admin/deliveries/${id}/assign`, { method: 'DELETE', headers: A });
  // A courier with an empty round is served 204 No Content, so the body is empty
  // and JSON.parse never runs. Normalise to [] or every .some() below throws.
  const round = async (t) => {
    const r = await j('/api/v1/courier/deliveries', { headers: auth(t) });
    return Array.isArray(r.body) ? r.body : [];
  };

  // ---- the core: move it to the other courier ----
  // Pin the starting courier FIRST. This check used to assume auto-dispatch had
  // parked the order on courier1, and that assumption silently rotted: the
  // dispatcher legitimately chose courier2, the explicit assign then correctly
  // returned 400 ("already assigned to this delivery"), and three assertions
  // failed for a reason that had nothing to do with manual reassignment.
  //
  // A check that depends on which courier the dispatcher picked can be written
  // to pass under either answer, so it proves nothing. Setting the origin
  // explicitly makes the move a real move.
  const whoPickedIt = (await j('/api/v1/admin/deliveries', { headers: A })).body
    .find((d) => d.id === created.id);
  console.log(`  auto-dispatch chose ${whoPickedIt?.courierEmail || 'nobody'}; pinning the origin`);
  await unassign(created.id);
  const pinned = await assign(created.id, c1.id);
  ok('the scratch order is pinned to courier1 before testing a move',
    pinned.status === 200 && pinned.body?.courierEmail === c1.email,
    `HTTP ${pinned.status}, courier=${pinned.body?.courierEmail}`);

  const r1 = await assign(created.id, c2.id);
  ok('reassign to courier2 succeeds', r1.status === 200, `HTTP ${r1.status} ${JSON.stringify(r1.body).slice(0, 120)}`);
  ok('reassigned delivery reports courier2', r1.body?.courierEmail === c2.email, r1.body?.courierEmail);
  ok('reassigned delivery is back to ASSIGNED', r1.body?.status === 'ASSIGNED', r1.body?.status);

  // Assert the round AFTER the second order is placed, otherwise courier1's
  // round is still empty and "courier1 does not see it" passes for the wrong
  // reason (an empty list trivially contains nothing).
  const c2RoundEarly = await round(c2T);
  ok('courier2 sees the delivery in their round',
    c2RoundEarly.some((d) => d.id === created.id), `${c2RoundEarly.length} stops`);
  const c1RoundEarly = await round(c1T);
  ok('courier1 does not see courier2\'s delivery',
    !c1RoundEarly.some((d) => d.id === created.id), `${c1RoundEarly.length} stops`);

  // ---- two couriers, one delivery each: the scenario asked for ----
  const other = (await j('/api/v1/client/deliveries', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${clientT}` },
    body: JSON.stringify({
      description: 'ASSIGN-TEST-2', pickupAddress: 'C, Casa', dropoffAddress: 'D, Casa',
      pickupLat: 33.60, pickupLng: -7.65, dropoffLat: 33.62, dropoffLng: -7.66,
    }),
  })).body;
  // Order 2 goes to courier1 by explicit admin action, NOT by auto-dispatch.
  // Letting the dispatcher pick and then asserting on the result is circular:
  // whichever courier it chose, the assertion could be written to pass, and the
  // whole branch could be skipped. Unassign first so the state is known.
  await j(`/api/v1/client/deliveries/${other.id}/pay`, { method: 'PATCH', headers: auth(clientT) });
  const autoAssigned = (await j('/api/v1/admin/deliveries', { headers: A })).body.find((d) => d.id === other.id);
  if (autoAssigned.courierId) await unassign(other.id);
  const r2 = await assign(other.id, c1.id);
  ok('second delivery assignable to courier1', r2.status === 200 && r2.body?.courierEmail === c1.email,
    `auto-dispatch had said ${autoAssigned.courierEmail || 'nobody'}; manual -> ${r2.body?.courierEmail}`);

  const second = (await j('/api/v1/admin/deliveries', { headers: A })).body.find((d) => d.id === other.id);
  ok('second delivery ended up on courier1', second.courierId === c1.id, second.courierEmail);

  const final = (await j('/api/v1/admin/deliveries', { headers: A })).body;
  const mine = final.filter((d) => [created.id, other.id].includes(d.id));
  const spread = new Set(mine.map((d) => d.courierEmail));
  ok('the two deliveries sit with two different couriers', spread.size === 2, [...spread].join(', '));
  console.log('  --- the scenario ---');
  mine.forEach((d) => console.log(`  ${d.description.padEnd(14)} ${d.status.padEnd(10)} ${d.courierEmail}`));
  ok('both are live work (ASSIGNED or IN_TRANSIT)',
    mine.every((d) => d.status === 'ASSIGNED' || d.status === 'IN_TRANSIT'),
    mine.map((d) => d.status).join(','));

  // The scenario, seen from each courier's own screen: courier1 holds exactly
  // their order, courier2 holds exactly theirs, and neither sees the other.
  const r1Courier = await round(c1T);
  const r2Courier = await round(c2T);
  ok('courier1\'s round holds their delivery', r1Courier.some((d) => d.id === other.id),
    r1Courier.map((d) => `${d.description}:${d.status}`).join(', '));
  ok('courier2\'s round holds their delivery', r2Courier.some((d) => d.id === created.id),
    r2Courier.map((d) => `${d.description}:${d.status}`).join(', '));
  ok('the couriers\' rounds do not overlap',
    !r1Courier.some((d) => r2Courier.some((e) => e.id === d.id)),
    `c1=${r1Courier.length} c2=${r2Courier.length}`);
  console.log('  --- courier1 screen ---');
  r1Courier.forEach((d) => console.log(`   ${d.id.slice(0, 8)} ${d.status.padEnd(10)} ${d.description}`));
  console.log('  --- courier2 screen ---');
  r2Courier.forEach((d) => console.log(`   ${d.id.slice(0, 8)} ${d.status.padEnd(10)} ${d.description}`));

  // ---- auto-dispatch balancing ----
  // The old dispatcher stopped at the FIRST courier in findAll() order that
  // could absorb the stop, so whichever courier happened to be created first ate
  // every order. To detect that, the loaded courier must be the FIRST one in the
  // list: if the busy courier is courier2 and courier1 is idle, first-fit and
  // best-fit agree, and the check passes under the regression too. Load the
  // first courier in the returned order, not a hardcoded one.
  await unassign(created.id);
  await unassign(other.id);
  const firstListed = couriers[0];
  const otherListed = couriers[1];
  console.log(`  findAll order: ${couriers.map((c) => c.email).join(' then ')}`);

  const loadIds = [];
  for (let i = 0; i < 2; i++) {
    const o = (await j('/api/v1/client/deliveries', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${clientT}` },
      body: JSON.stringify({
        description: `ASSIGN-LOAD-${i + 1}`, pickupAddress: 'E, Casa', dropoffAddress: 'F, Casa',
        pickupLat: 33.58, pickupLng: -7.61, dropoffLat: 33.60, dropoffLng: -7.62,
      }),
    })).body;
    loadIds.push(o.id);
    await assign(o.id, firstListed.id); // both stops onto the FIRST courier
  }
  const firstListedTok = firstListed.email === c1.email ? c1T : c2T;
  // Count only the stops this script loaded. Counting the courier's whole round
  // asserts on the demo data, so any pre-existing active stop -- the seeded
  // "2 boxes - Maarif" is ASSIGNED -- makes the precondition fail and the check
  // below it unverifiable. The precondition has to be owned, not borrowed.
  const busy = (await round(firstListedTok)).filter((d) =>
    ['ASSIGNED', 'IN_TRANSIT'].includes(d.status) && loadIds.includes(d.id));
  const loadedOk = busy.length === 2;
  ok('the first-listed courier is deliberately loaded with 2 active stops',
    loadedOk, `${firstListed.email} has ${busy.length} of the 2 test stops`);

  const probe = (await j('/api/v1/client/deliveries', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${clientT}` },
    body: JSON.stringify({
      description: 'ASSIGN-PROBE', pickupAddress: 'G, Casa', dropoffAddress: 'H, Casa',
      pickupLat: 33.62, pickupLng: -7.64, dropoffLat: 33.64, dropoffLng: -7.65,
    }),
  })).body;
  await j(`/api/v1/client/deliveries/${probe.id}/pay`, { method: 'PATCH', headers: auth(clientT) });
  const probed = (await j('/api/v1/admin/deliveries', { headers: A })).body.find((d) => d.id === probe.id);
  console.log(`  --- auto-dispatch: ${firstListed.email} (first in list) loaded with 2;`
    + ` new paid order went to ${probed.courierEmail || 'nobody'} ---`);
  // Skipping this when the precondition failed would hide the failure rather than
  // report it. First-fit and best-fit both send the stop to the idle courier
  // unless the first-listed one is genuinely loaded, so an unloaded fleet makes
  // this assertion meaningless. It fails loudly instead.
  ok('a paid order skips the loaded first-listed courier',
    loadedOk && probed.courierId === otherListed.id,
    `went to ${probed.courierEmail}; ${firstListed.email} is the loaded first-listed courier, ${otherListed.email} is idle`);

  loadIds.push(probe.id);
  for (const id of loadIds) await j(`/api/v1/admin/deliveries/${id}`, { method: 'DELETE', headers: A });

  // ---- guards ----
  // The balance test above freed the fleet, so put `created` back on courier2:
  // the guards need a delivery that HAS a courier, otherwise they all 400 for
  // the wrong reason and prove nothing.
  await assign(created.id, c2.id);
  const again = await assign(created.id, c2.id);
  ok('assigning the same courier twice is refused', again.status >= 400, `HTTP ${again.status}: ${JSON.stringify(again.body).slice(0, 100)}`);

  const un = await unassign(created.id);
  ok('unassign succeeds', un.status === 200, `HTTP ${un.status}`);
  ok('unassigned delivery has no courier', !un.body?.courierEmail, un.body?.courierEmail || 'nobody');
  ok('unassigned delivery returns to PENDING', un.body?.status === 'PENDING', un.body?.status);

  const un2 = await unassign(created.id);
  ok('unassigning twice is refused', un2.status >= 400, `HTTP ${un2.status}: ${JSON.stringify(un2.body).slice(0, 100)}`);

  const nonCourier = (await j('/api/v1/admin/users', { headers: A })).body.find((u) => u.role === 'CLIENT');
  const badRole = await assign(created.id, nonCourier.id);
  ok('a client cannot be assigned as courier', badRole.status >= 400, `HTTP ${badRole.status}: ${JSON.stringify(badRole.body).slice(0, 100)}`);

  // ---- authorization: the client must not be able to do any of this ----
  const clientAssign = await j(`/api/v1/admin/deliveries/${other.id}/assign`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${clientT}` },
    body: JSON.stringify({ courierId: c1.id }),
  });
  ok('a client cannot assign couriers', clientAssign.status === 403 || clientAssign.status === 401, `HTTP ${clientAssign.status}`);
  const clientUnassign = await j(`/api/v1/admin/deliveries/${other.id}/assign`, {
    method: 'DELETE', headers: auth(clientT),
  });
  ok('a client cannot unassign couriers', clientUnassign.status === 403 || clientUnassign.status === 401, `HTTP ${clientUnassign.status}`);
  const anon = await j(`/api/v1/admin/deliveries/${other.id}/assign`, { method: 'DELETE' });
  ok('an anonymous caller cannot unassign', anon.status === 403 || anon.status === 401, `HTTP ${anon.status}`);

  // ---- cleanup: leave the demo data as it was ----
  await j(`/api/v1/admin/deliveries/${created.id}`, { method: 'DELETE', headers: A });
  await j(`/api/v1/admin/deliveries/${other.id}`, { method: 'DELETE', headers: A });
  const after = (await j('/api/v1/admin/deliveries', { headers: A })).body;
  const leftovers = after.filter((d) => /^(ASSIGN-TEST|ASSIGN-LOAD|ASSIGN-PROBE)/.test(d.description));
  ok('every scratch order cleaned up', leftovers.length === 0,
    leftovers.length ? leftovers.map((d) => d.description).join(', ') : `${after.length} real deliveries remain`);

  report();
})();

function report() {
  results.forEach(([s, n, e]) => console.log(`  ${s} ${n}${e ? '\n        << ' + e : ''}`));
  const ko = results.filter(([s]) => s === 'KO  ').length;
  console.log(ko === 0 ? 'ALL CHECKS OK' : `${ko} FAILURES`);
  process.exit(ko === 0 ? 0 : 1);
}
