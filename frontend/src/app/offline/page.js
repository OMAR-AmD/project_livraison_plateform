/**
 * Shown by the service worker when a navigation fails with no cached copy:
 * the courier lost signal mid-round. Static by design — no data fetching, so
 * it renders with zero network.
 */
export default function OfflinePage() {
  return (
    <main className="flex min-h-[70vh] flex-col items-center justify-center gap-4 px-6 text-center">
      <p className="font-mono text-5xl font-bold text-signal-500">···</p>
      <h1 className="text-xl font-bold text-content">You are offline</h1>
      <p className="max-w-sm text-sm text-content-muted">
        No connection and this page is not cached. Your orders are safe on the
        server — move back into coverage and reload.
      </p>
      <a href="/dashboard/deliveries" className="btn-primary">
        Retry
      </a>
    </main>
  );
}
