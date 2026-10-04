'use client';

import { useEffect } from 'react';

/**
 * Registers /sw.js in production only. In `next dev` the worker would cache
 * half-compiled routes and make every code change look broken, so it stays
 * off there — and every Playwright suite that needs a pristine network can
 * run against dev.
 */
export default function ServiceWorkerRegister() {
  useEffect(() => {
    if (process.env.NODE_ENV !== 'production') return;
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // Offline support is a bonus, not a requirement: a failed registration
      // must never break the app.
    });
  }, []);
  return null;
}
