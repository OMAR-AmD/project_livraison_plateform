'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useAuth } from '@/context/AuthContext';
import Navbar from '@/components/Navbar';

const PILLARS = [
  {
    title: 'Autonomous dispatch',
    body: 'A time-window vehicle-routing solver assigns each new order to the courier whose existing round can still absorb it inside the 45-minute promise.',
    points: ['OR-Tools DVRP solver', 'OSRM travel-time matrix', 'No dispatcher required'],
  },
  {
    title: 'Honest pricing',
    body: 'Every quote is computed server-side from the real road route. The figure shown at checkout is the figure that gets charged.',
    points: ['Road distance, not straight line', 'Quote then capture', 'Revenue on paid orders only'],
  },
  {
    title: 'Fully self-hosted',
    body: 'Routing, embeddings and the language model all run on your own hardware. The platform keeps working with no outbound internet connection.',
    points: ['Self-hosted OSRM', 'Local LLM via Ollama', 'pgvector retrieval'],
  },
];

const CAPABILITIES = [
  ['Route optimisation', 'Multi-stop rounds solved against a live travel-time matrix, not straight-line guesses.'],
  ['Live courier tracking', 'Positions stream over WebSocket and expire from cache, so a stale marker can never be shown as live.'],
  ['Local RAG assistant', 'A local model answers customer questions grounded in the FAQ and live order state.'],
  ['Order cancellation by agent', 'The assistant can cancel an order, but only ever the requesting customer’s own, and only while it is still pending.'],
  ['Live operations map', 'Every active delivery on one map, with the optimised sequence the driver will actually follow.'],
  ['Self-service accounts', 'Clients and couriers sign up themselves; administrator access is granted out of band.'],
];

export default function HomePage() {
  const { isAuthenticated, user } = useAuth();
  const [active, setActive] = useState(0);

  // Gentle rotation so the hero does not read as a static screenshot.
  useEffect(() => {
    const id = setInterval(() => setActive((i) => (i + 1) % PILLARS.length), 6000);
    return () => clearInterval(id);
  }, []);

  return (
    <div className="min-h-screen bg-canvas flex flex-col">
      <Navbar />

      {/* ── Hero ─────────────────────────────────────────────────────── */}
      <section className="relative overflow-hidden border-b border-line">
        {/* Single soft accent wash — the only decorative colour on the page. */}
        <div
          className="pointer-events-none absolute -top-40 right-[-10%] h-[520px] w-[520px] rounded-full bg-signal-500/[0.07] blur-3xl"
          aria-hidden="true"
        />

        <div className="relative mx-auto max-w-6xl px-6 py-24 sm:py-32">
          <div className="max-w-3xl">
            <span className="inline-flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-1 text-xs font-semibold text-content-muted">
              <span className="h-1.5 w-1.5 rounded-full bg-signal-500" />
              Runs entirely on your own infrastructure
            </span>

            <h1 className="mt-6 text-4xl font-bold tracking-tight text-content sm:text-6xl">
              Last-mile delivery that
              <span className="text-signal-500"> dispatches itself</span>
            </h1>

            <p className="mt-6 max-w-2xl text-lg leading-relaxed text-content-muted">
              SwiftDeliver assigns incoming orders, prices them from the real road route and
              keeps customers informed — without a dispatcher, and without calling a single
              paid cloud API.
            </p>

            <div className="mt-10 flex flex-wrap gap-3">
              <Link href={isAuthenticated ? '/dashboard' : '/register'} className="btn-primary">
                {isAuthenticated ? 'Open dashboard' : 'Create an account'}
              </Link>
              <Link href="/login" className="btn-secondary">
                Sign in
              </Link>
            </div>

            {isAuthenticated && user && (
              <p className="mt-4 text-sm text-content-faint">
                Signed in as {user.email} · {user.role}
              </p>
            )}
          </div>

          {/* ── Rotating capability panel ─────────────────────────────── */}
          <div className="mt-16 grid gap-4 sm:grid-cols-3">
            {PILLARS.map((pillar, i) => (
              <article
                key={pillar.title}
                onMouseEnter={() => setActive(i)}
                className={`surface p-6 transition-colors ${
                  i === active ? 'border-line-strong' : 'hover:border-line'
                }`}
              >
                <div
                  className={`h-1 w-10 rounded-full transition-colors ${
                    i === active ? 'bg-signal-500' : 'bg-line-strong'
                  }`}
                />
                <h2 className="mt-4 text-base font-bold text-content">{pillar.title}</h2>
                <p className="mt-2 text-sm leading-relaxed text-content-muted">{pillar.body}</p>
                <ul className="mt-4 space-y-1.5">
                  {pillar.points.map((point) => (
                    <li key={point} className="flex items-start gap-2 text-xs text-content-faint">
                      <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-content-faint" />
                      {point}
                    </li>
                  ))}
                </ul>
              </article>
            ))}
          </div>
        </div>
      </section>

      {/* ── Capabilities ──────────────────────────────────────────────── */}
      <section className="border-b border-line">
        <div className="mx-auto max-w-6xl px-6 py-20">
          <h2 className="section-title">What it does</h2>
          <div className="mt-8 grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-2 lg:grid-cols-3">
            {CAPABILITIES.map(([title, body]) => (
              <div key={title} className="bg-surface p-6">
                <h3 className="text-sm font-bold text-content">{title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-content-muted">{body}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── Closing call to action ────────────────────────────────────── */}
      <section className="mx-auto w-full max-w-6xl flex-1 px-6 py-20">
        <div className="surface flex flex-col items-start gap-6 p-10 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-xl font-bold text-content">See it dispatch a real order</h2>
            <p className="mt-2 max-w-xl text-sm text-content-muted">
              Create an account, book a delivery and watch a courier get assigned, routed and
              tracked in real time — with no external service in the loop.
            </p>
          </div>
          <Link href={isAuthenticated ? '/dashboard' : '/register'} className="btn-primary shrink-0">
            {isAuthenticated ? 'Open dashboard' : 'Get started'}
          </Link>
        </div>
      </section>

      <footer className="border-t border-line">
        <div className="mx-auto max-w-6xl px-6 py-8 text-xs text-content-faint">
          SwiftDeliver — self-hosted last-mile delivery platform.
        </div>
      </footer>
    </div>
  );
}
