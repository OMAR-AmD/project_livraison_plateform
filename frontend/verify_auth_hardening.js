/**
 * Is the authentication hardening real on the running platform?
 *
 * WHAT THIS CHECKS
 *   1. Signing out revokes the token server-side. The token works before the
 *      call, the call succeeds, and the SAME token is refused afterwards --
 *      while a freshly minted one still works. (A JWT is normally valid until
 *      it expires; this is the difference between "logged out" and "deleted
 *      from this browser".)
 *   2. A burst of wrong passwords against one account is refused with 429 and a
 *      Retry-After, rather than an endless stream of 401s.
 *
 * WHY BOTH DIRECTIONS
 *   Revocation is checked as works -> logout -> refused, then works again for a
 *   new token. An endpoint that always refuses fails the first call; one that
 *   always accepts fails the third. There is no constant answer that passes.
 *   The limiter is checked as "the early guesses are 401, the one past the
 *   threshold is 429". A limiter stuck on fails the early guesses; a limiter
 *   that does nothing fails the last.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *   It never sends wrong passwords for a real demo account. The limiter part
 *   uses a throwaway address, and a distinct X-Forwarded-For per run, so the
 *   per-client bucket is fresh and no account the teacher might use is locked.
 *   (The header is trivially spoofable, which is fine against the local backend
 *   and is why the backend keys the primary limit on the account, not the IP.)
 *
 * NOTE: logging out admin revokes every other admin session too. That is the
 * feature, but it will sign out a browser that is logged in as admin.
 *
 * RUN
 *   node verify_auth_hardening.js
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

async function login(email, password, extraHeaders = {}) {
  const r = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...extraHeaders },
    body: JSON.stringify({ email, password }),
  });
  const body = await r.json().catch(() => null);
  return { status: r.status, ok: r.ok, body, retryAfter: r.headers.get('retry-after') };
}

async function me(token) {
  const r = await fetch(`${BASE}/users/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await r.json().catch(() => null);
  return { status: r.status, ok: r.ok, body };
}

async function logout(token) {
  const r = await fetch(`${BASE}/auth/logout`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = r.status === 204 ? null : await r.json().catch(() => null);
  return { status: r.status, ok: r.ok, body };
}

/** 401 (no auth) and 403 (auth present but rejected) both mean "not served". */
const refused = (status) => status === 401 || status === 403;

async function main() {
  console.log('='.repeat(72));
  console.log('Live authentication-hardening check (real API, real database)');
  console.log('='.repeat(72));

  // ================= logout revokes the token =================
  console.log('\n-- sign-out revokes the token server-side --');

  const before = await login('admin@swift.com', PASSWORD);
  check('an admin can sign in', before.ok && typeof before.body?.token === 'string',
    `status ${before.status}`);
  const staleToken = before.body?.token;

  const meBefore = await me(staleToken);
  check('the token is accepted before sign-out', meBefore.ok, `status ${meBefore.status}`);
  check('and it resolves to the account that signed in',
    meBefore.body?.email === 'admin@swift.com',
    meBefore.body?.email || JSON.stringify(meBefore.body));

  const out = await logout(staleToken);
  check('sign-out answers 2xx', out.ok, `status ${out.status}`);

  const meAfter = await me(staleToken);
  check('the same token is refused after sign-out', refused(meAfter.status),
    `status ${meAfter.status}`);
  check('the refusal does not serve the profile anyway',
    meAfter.body?.email !== 'admin@swift.com',
    JSON.stringify(meAfter.body));

  const fresh = await login('admin@swift.com', PASSWORD);
  const meFresh = fresh.ok ? await me(fresh.body.token) : { ok: false, status: 0 };
  check('a fresh sign-in mints a token that works', fresh.ok && meFresh.ok,
    `login ${fresh.status}, me ${meFresh.status}`);

  // ================= failed-login rate limit =================
  console.log('\n-- a burst of wrong passwords is cut off --');

  // A distinct client per run, so repeated runs do not accumulate into the
  // client bucket and never touch a real account's counter.
  const client = `198.51.100.${1 + Math.floor(Math.random() * 250)}`;
  const target = `nobody-${Date.now()}@example.com`;
  const headers = { 'X-Forwarded-For': client };

  let early = [];
  for (let i = 1; i <= 5; i++) {
    const r = await login(target, 'definitely-not-the-password', headers);
    early.push(r.status);
  }
  check('the first five wrong-password attempts are 401, not 429',
    early.every((s) => s === 401),
    early.join(', '));

  const sixth = await login(target, 'definitely-not-the-password', headers);
  check('the sixth attempt is refused with 429', sixth.status === 429,
    `status ${sixth.status}`);
  check('the refusal carries a Retry-After the client can honour',
    sixth.retryAfter != null && Number(sixth.retryAfter) >= 1,
    `Retry-After: ${sixth.retryAfter}`);
  check('the refusal says how long to wait',
    /Try again in \d+ second/.test(sixth.body?.message || ''),
    sixth.body?.message || JSON.stringify(sixth.body));

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
