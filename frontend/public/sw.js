/* SwiftDeliver service worker: installable shell + offline fallback.
 *
 * Deliberately hand-rolled instead of next-pwa: the caching policy is three
 * lines and must be auditable, because the wrong policy here silently serves
 * stale order state or, worse, caches live API answers.
 *
 * Policy:
 * - Navigations: network first, then cache, then /offline. A courier losing
 *   signal mid-round gets an honest offline page, never a frozen dashboard.
 * - Same-origin static assets (/_next/static, /leaflet, /icons, /fonts):
 *   cache first. They are content-hashed or versioned by filename.
 * - /api/* and /osrm/*: network only. Live positions, prices and dispatch
 *   state must never come from a cache.
 * - Everything else (tiles, Nominatim): network only. OSM tiles are tens of
 *   thousands of files; caching them would fill the quota with map fragments
 *   instead of the app shell.
 */
const VERSION = 'swiftdeliver-v1';
const SHELL = ['/', '/login', '/offline', '/manifest.webmanifest'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

function isStaticAsset(url) {
  return (
    url.pathname.startsWith('/_next/static/') ||
    url.pathname.startsWith('/leaflet/') ||
    url.pathname.startsWith('/icons/') ||
    url.pathname.startsWith('/fonts/')
  );
}

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  let url;
  try {
    url = new URL(request.url);
  } catch {
    return;
  }
  if (url.origin !== self.location.origin) return;

  // Live data: never cache, never serve stale.
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/osrm/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((res) => {
          const copy = res.clone();
          caches.open(VERSION).then((cache) => cache.put(request, copy));
          return res;
        })
        .catch(() =>
          caches.match(request).then((hit) => hit || caches.match('/offline')),
        ),
    );
    return;
  }

  if (isStaticAsset(url)) {
    event.respondWith(
      caches.match(request).then(
        (hit) =>
          hit ||
          fetch(request).then((res) => {
            const copy = res.clone();
            caches.open(VERSION).then((cache) => cache.put(request, copy));
            return res;
          }),
      ),
    );
  }
});
