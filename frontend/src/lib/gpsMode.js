/**
 * Decides whether the courier app should broadcast a simulated route or the
 * device's real GPS position.
 *
 * Real geolocation is only usable when the browser exposes the API, the page
 * is a secure context (HTTPS or localhost) and the device is actually a phone.
 * Everything else — the laptop used for the demo and the automated rehearsal —
 * falls back to the simulated route, which is deterministic and offline-safe.
 *
 * The logic lives here, separate from the component, so the truth table can be
 * exercised without a browser.
 */

/**
 * @param {{ hasGeolocation?: boolean, secureContext?: boolean, isMobile?: boolean }} env
 * @returns {boolean} true when the caller should SIMULATE (i.e. not use real GPS)
 */
export function defaultToSimulation(env) {
  const { hasGeolocation, secureContext, isMobile } = env || {};
  const canUseRealGps = Boolean(hasGeolocation) && Boolean(secureContext) && Boolean(isMobile);
  return !canUseRealGps;
}

/**
 * Reads the current environment in a way that is safe during SSR.
 * @returns {{ hasGeolocation: boolean, secureContext: boolean, isMobile: boolean }}
 */
export function detectGpsEnvironment() {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') {
    return { hasGeolocation: false, secureContext: false, isMobile: false };
  }

  const ua = navigator.userAgent || '';
  const coarsePointer =
    typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;
  const mobileUserAgent = /Android|iPhone|iPad|iPod|Mobi|Mobile/i.test(ua);
  const isMobile = mobileUserAgent || (navigator.maxTouchPoints > 1 && coarsePointer);

  return {
    hasGeolocation: Boolean(navigator.geolocation),
    // `isSecureContext` is undefined on very old browsers; treat that as secure
    // so the geolocation error handler (not this check) reports the real problem.
    secureContext: window.isSecureContext !== false,
    isMobile,
  };
}
