'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import { useToast } from '@/components/Toast';

/**
 * Split auth layout shared by /login and /register: a value proposition on one
 * side, the form on the other. On mobile the panel stacks above the form.
 */
export default function AuthShell({ title, subtitle, children, footer }) {
  return (
    <div className="grid min-h-screen bg-canvas lg:grid-cols-2">
      {/* ── Form ──────────────────────────────────────────────────────── */}
      <div className="flex flex-col justify-center px-6 py-12 sm:px-12">
        <div className="mx-auto w-full max-w-sm">
          <Link href="/" className="mb-10 inline-flex items-center gap-2.5">
            <span className="flex h-8 w-8 items-center justify-center rounded-md bg-signal-500 text-white">
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
                <path strokeLinecap="square" strokeLinejoin="miter" d="M2 12h5l3-8 4 16 3-8h5" />
              </svg>
            </span>
            <span className="text-lg font-bold tracking-tight text-content">SwiftDeliver</span>
          </Link>

          <h1 className="text-2xl font-bold tracking-tight text-content">{title}</h1>
          <p className="mt-1.5 text-sm text-content-muted">{subtitle}</p>

          <div className="mt-8">{children}</div>

          <div className="mt-8 border-t border-line pt-6 text-sm text-content-muted">
            {footer}
          </div>
        </div>
      </div>

      {/* ── Value proposition ─────────────────────────────────────────── */}
      <aside className="relative hidden overflow-hidden border-l border-line bg-surface-sunken lg:block">
        <div
          className="pointer-events-none absolute -right-24 top-1/4 h-[420px] w-[420px] rounded-full bg-signal-500/[0.06] blur-3xl"
          aria-hidden="true"
        />
        <div className="relative flex h-full flex-col justify-center px-14">
          <h2 className="max-w-sm text-3xl font-bold leading-tight tracking-tight text-content">
            Delivery operations that run themselves.
          </h2>
          <p className="mt-4 max-w-sm text-sm leading-relaxed text-content-muted">
            Routing, optimisation and the customer-facing assistant all execute on your own
            hardware. No dispatch desk, no paid third-party API, no data leaving the building.
          </p>

          <dl className="mt-12 max-w-sm space-y-6">
            {[
              ['45 min', 'Maximum promised transit time, enforced by the router and the booking form alike'],
              ['0', 'External services required at runtime'],
              ['3 roles', 'Clients, couriers and administrators, each with their own workspace'],
            ].map(([stat, caption]) => (
              <div key={stat}>
                <dt className="stat-value">{stat}</dt>
                <dd className="mt-1 text-sm leading-relaxed text-content-muted">{caption}</dd>
              </div>
            ))}
          </dl>
        </div>
      </aside>
    </div>
  );
}
