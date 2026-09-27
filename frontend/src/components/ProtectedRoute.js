'use client';

/**
 * ProtectedRoute
 *
 * Auth-guard wrapper component.  Place it around any page or layout that
 * requires the user to be logged in:
 *
 *   <ProtectedRoute>
 *     <DashboardPage />
 *   </ProtectedRoute>
 *
 * While the auth state is still hydrating it shows a premium full-screen
 * loading spinner.  Once hydration finishes, unauthenticated users are
 * silently redirected to /login.
 */

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';

export default function ProtectedRoute({ children }) {
  const { loading, isAuthenticated } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && !isAuthenticated) {
      router.push('/login');
    }
  }, [loading, isAuthenticated, router]);

  /* ---- loading state ---- */
  if (loading) {
    return (
      <div className="fixed inset-0 z-[9999] flex flex-col items-center justify-center bg-canvas">
        <div className="relative flex h-12 w-12 items-center justify-center">
          {/* Track ring */}
          <div className="absolute inset-0 rounded-full border-2 border-line" />

          {/* Spinning arc in the accent colour */}
          <svg
            className="absolute inset-0 h-full w-full animate-spin"
            viewBox="0 0 48 48"
            fill="none"
          >
            <circle
              cx="24"
              cy="24"
              r="22"
              stroke="currentColor"
              className="text-signal-500"
              strokeWidth="2.5"
              strokeLinecap="round"
              strokeDasharray="110 200"
            />
          </svg>
        </div>

        {/* Label */}
        <p className="mt-6 text-sm font-medium tracking-wide text-content-faint">
          Loading&hellip;
        </p>
      </div>
    );
  }

  /* ---- not authenticated (redirect in progress) ---- */
  if (!isAuthenticated) {
    return null;
  }

  /* ---- authenticated ---- */
  return children;
}
