'use client';

import { useState } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import Sidebar from '@/components/Sidebar';

export default function DashboardLayout({ children }) {
  // The drawer state lives here so the mobile top bar can own the trigger
  // button. Previously the trigger floated over the content at a fixed
  // position, which meant it sat on top of the page title on a phone.
  const [navOpen, setNavOpen] = useState(false);

  return (
    <ProtectedRoute>
      <div className="min-h-screen bg-canvas">
        <Sidebar open={navOpen} onOpenChange={setNavOpen} />

        {/* Mobile top bar — reserves the row the menu button occupies. */}
        <header className="sticky top-0 z-30 flex items-center gap-3 border-b border-line bg-canvas/90 px-4 py-3 backdrop-blur-md md:hidden">
          <button
            onClick={() => setNavOpen(true)}
            aria-label="Open navigation"
            aria-expanded={navOpen}
            className="-ml-1 shrink-0 rounded-md border border-line bg-surface-raised p-2 text-content-muted transition-colors hover:text-content"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5" />
            </svg>
          </button>
          <span className="text-base font-bold tracking-tight text-content">SwiftDeliver</span>
        </header>

        <main className="px-4 py-6 sm:px-6 md:ml-64 md:px-8 md:py-8">
          {children}
        </main>
      </div>
    </ProtectedRoute>
  );
}
