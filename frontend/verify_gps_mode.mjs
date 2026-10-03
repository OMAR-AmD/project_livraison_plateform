/**
 * Does the courier app choose the right location mode by default?
 *
 * WHAT THIS CHECKS
 * Real GPS is only usable inside a secure context (HTTPS or localhost) and on a
 * device that is actually a phone. Everywhere else the app must fall back to the
 * deterministic simulated route — that is what the laptop demo and the automated
 * rehearsal depend on. This exercises the truth table of defaultToSimulation()
 * and the SSR-safe detector.
 *
 * WHY IT IS NOT A TRIVIAL CHECK
 * The four cases pull in opposite directions: a phone on HTTPS MUST use real GPS,
 * while a desktop, an insecure origin and a browser without geolocation MUST NOT.
 * A function hard-wired to either answer fails at least one branch.
 *
 * MUTATION
 * Flip the return in src/lib/gpsMode.js to `return true` (or `return false`) and
 * re-run: this script exits non-zero. The desktop->simulate case is also asserted
 * end-to-end by rehearse_demo.js ("courier defaults to the simulated route").
 *
 * RUN
 * node verify_gps_mode.mjs
 */

import { defaultToSimulation, detectGpsEnvironment } from './src/lib/gpsMode.js';

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

// A phone opened on the deployed (HTTPS) site: the feature that was requested.
check(
  'phone on HTTPS uses real GPS (not simulated)',
  defaultToSimulation({ hasGeolocation: true, secureContext: true, isMobile: true }) === false
);

// The laptop used for the live demo must stay deterministic.
check(
  'desktop on HTTPS simulates',
  defaultToSimulation({ hasGeolocation: true, secureContext: true, isMobile: false }) === true
);

// Phones on the LAN are served over plain HTTP: geolocation is blocked there.
check(
  'phone on an insecure origin simulates',
  defaultToSimulation({ hasGeolocation: true, secureContext: false, isMobile: true }) === true
);

// No geolocation API at all: nothing to watch, so simulate.
check(
  'browser without geolocation simulates',
  defaultToSimulation({ hasGeolocation: false, secureContext: true, isMobile: true }) === true
);

// Called without an argument (defensive) it must not crash and must simulate.
check('missing environment simulates', defaultToSimulation() === true);

// On the server there is no window/navigator; the detector must not throw and
// must report nothing usable, which makes the default "simulate".
const ssrEnv = detectGpsEnvironment();
check(
  'detector is SSR-safe',
  ssrEnv.hasGeolocation === false && ssrEnv.secureContext === false && ssrEnv.isMobile === false,
  JSON.stringify(ssrEnv)
);

console.log(`\n${passed} passed, ${failures.length} failed`);
if (failures.length > 0) {
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exit(1);
}
