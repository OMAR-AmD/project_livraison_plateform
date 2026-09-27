'use client';

import { useEffect, useState } from 'react';
import Modal from './Modal';
import { courierOptimizeRoute } from '@/lib/api';
import dynamic from 'next/dynamic';
import { useToast } from '@/components/Toast';

const RouteMap = dynamic(() => import('./OptimizedRouteMap'), { ssr: false });

function formatDuration(seconds) {
  if (!seconds && seconds !== 0) return '—';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return s > 0 ? `${m} min ${s} sec` : `${m} min`;
}

export default function OptimizedRouteModal({ isOpen, onClose, courierPos }) {
  const [loading, setLoading] = useState(false);
  const [routeData, setRouteData] = useState(null);
  const { addToast } = useToast();

  useEffect(() => {
    if (!isOpen) {
      setRouteData(null);
      return;
    }
    if (routeData || !courierPos) return;

    setLoading(true);
    courierOptimizeRoute(courierPos.lat, courierPos.lng)
      .then((data) => {
        setRouteData(data);
        setLoading(false);
      })
      .catch((err) => {
        console.error('Failed to optimise route', err);
        addToast(err.message || 'Failed to optimise the route', 'error');
        setLoading(false);
        onClose();
      });
  }, [isOpen, courierPos, addToast, onClose, routeData]);

  const stops = routeData?.orderedWaypoints?.length ?? 0;

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Optimised round"
      description="Solved against live road travel times, subject to the 45-minute transit limit"
      size="lg"
    >
      {loading ? (
        <div className="flex flex-col items-center justify-center gap-4 py-16">
          <svg className="h-8 w-8 animate-spin text-signal-500" viewBox="0 0 24 24" fill="none">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
            <path
              className="opacity-90"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
            />
          </svg>
          <p className="text-sm font-medium text-content-muted">Solving the vehicle routing problem…</p>
        </div>
      ) : routeData ? (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <div className="rounded-lg border border-line bg-surface-raised px-4 py-3">
              <p className="stat-label">Total travel</p>
              <p className="mt-1 text-xl font-bold text-content tabular">
                {formatDuration(routeData.totalTimeSeconds)}
              </p>
            </div>
            <div className="rounded-lg border border-line bg-surface-raised px-4 py-3">
              <p className="stat-label">Stops</p>
              <p className="mt-1 text-xl font-bold text-content tabular">{stops}</p>
            </div>
          </div>

          <div className="overflow-hidden rounded-lg border border-line">
            <RouteMap orderedWaypoints={routeData.orderedWaypoints} courierPos={courierPos} />
          </div>

          {routeData.routeLog?.length > 0 && (
            <details className="group rounded-lg border border-line bg-surface-raised">
              <summary className="cursor-pointer list-none px-4 py-3 text-sm font-semibold text-content marker:hidden">
                <span className="flex items-center justify-between">
                  Step-by-step plan
                  <svg
                    className="h-4 w-4 text-content-faint transition-transform group-open:rotate-180"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    strokeWidth={2}
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                  </svg>
                </span>
              </summary>
              <ol className="space-y-1.5 border-t border-line px-4 py-3 text-sm text-content-muted">
                {routeData.routeLog.map((entry, idx) => (
                  <li key={idx} className="flex gap-3">
                    <span className="w-5 shrink-0 text-right text-xs font-semibold text-content-faint tabular">
                      {idx + 1}
                    </span>
                    <span className="min-w-0">{entry}</span>
                  </li>
                ))}
              </ol>
            </details>
          )}
        </div>
      ) : null}
    </Modal>
  );
}
