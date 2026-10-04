'use client';

import { useEffect, useRef, useState } from 'react';
import dynamic from 'next/dynamic';
import { clientGetCourierLocation } from '@/lib/api';
import { StatusPill } from '@/components/StatusPill';

// Leaflet touches `window`, so the map is never server-rendered.
const RouteMap = dynamic(() => import('./OptimizedRouteMap'), { ssr: false });

const POLL_MS = 2000;
const STALE_MS = 30000;

/**
 * The client's always-visible view of their active order.
 *
 * Deliberately a section of the page rather than a full-screen map: it is sized
 * to sit above the order list so the client keeps the list in view. The data
 * source is the same ownership-checked endpoint the "Track" modal uses, so a
 * client can only ever see their own delivery; passing someone else's id here
 * would return the generic "not yours / not found" answer, not their position.
 */
export default function ClientLiveMap({ delivery }) {
  const [courierPos, setCourierPos] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [isStale, setIsStale] = useState(false);
  const hasConnectedRef = useRef(false);

  const deliveryId = delivery?.id;

  useEffect(() => {
    if (!deliveryId) return;

    let cancelled = false;
    setCourierPos(null);
    setLastUpdated(null);
    setIsStale(false);
    hasConnectedRef.current = false;

    const fetchLocation = () => {
      clientGetCourierLocation(deliveryId)
        .then((data) => {
          if (cancelled) return;
          if (data && data.latitude != null && data.longitude != null) {
            hasConnectedRef.current = true;
            setCourierPos({ lat: data.latitude, lng: data.longitude });
            setLastUpdated(new Date());
            setIsStale(false);
          } else if (hasConnectedRef.current) {
            // A position existed and has gone: the Redis key expired, so the
            // courier is genuinely no longer broadcasting. Saying so beats a
            // frozen marker on screen. Before the first fix this stays
            // "connecting", which is the honest label.
            setIsStale(true);
          }
        })
        .catch(() => {
          if (cancelled) return;
          // The endpoint answers 404 while the courier has no fix to give. That
          // is the normal state before the first broadcast, so it must read as
          // "connecting", not as a lost signal. Only a fix that existed and
          // then stopped arriving is a lost signal.
          if (hasConnectedRef.current) setIsStale(true);
        });
    };

    fetchLocation();
    const poll = setInterval(fetchLocation, POLL_MS);
    const staleCheck = setInterval(() => {
      setLastUpdated((prev) => {
        if (prev && Date.now() - prev.getTime() > STALE_MS) setIsStale(true);
        return prev;
      });
    }, 5000);

    return () => {
      cancelled = true;
      clearInterval(poll);
      clearInterval(staleCheck);
    };
  }, [deliveryId]);

  if (!delivery) return null;

  const waypoints = [];
  if (delivery.pickupLat != null && delivery.pickupLng != null) {
    waypoints.push({
      latitude: delivery.pickupLat,
      longitude: delivery.pickupLng,
      type: 'PICKUP',
    });
  }
  if (delivery.dropoffLat != null && delivery.dropoffLng != null) {
    waypoints.push({
      latitude: delivery.dropoffLat,
      longitude: delivery.dropoffLng,
      type: 'DROPOFF',
    });
  }

  const hasPosition = courierPos != null;
  const tone = isStale ? 'text-warn-400' : hasPosition ? 'text-ok-400' : 'text-content-faint';

  return (
    <section
      data-testid="client-live-map"
      aria-label="Live map of your active delivery"
      className="surface overflow-hidden"
    >
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-content">Live tracking</h2>
          <p className="truncate text-xs text-content-muted" title={delivery.description}>
            {delivery.description} · {delivery.pickupAddress} → {delivery.dropoffAddress}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-3">
          <StatusPill status={delivery.status} />
          <span
            data-testid="client-live-map-status"
            className={`inline-flex items-center gap-2 text-xs font-medium ${tone}`}
          >
            <span className="relative flex h-2 w-2">
              {hasPosition && !isStale ? (
                <>
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-ok-400 opacity-75" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-ok-500" />
                </>
              ) : (
                <span
                  className={`inline-flex h-2 w-2 rounded-full ${
                    isStale ? 'bg-warn-500' : 'bg-ink-500'
                  }`}
                />
              )}
            </span>
            {isStale
              ? 'Signal lost'
              : hasPosition
                ? `Live · ${lastUpdated.toLocaleTimeString('en-GB')}`
                : 'Connecting…'}
          </span>
        </div>
      </div>

      {/* Moderate height on purpose: a section of the page, not the admin's
          full-height fleet map. */}
      <div className="h-[300px] sm:h-[340px]">
        <RouteMap orderedWaypoints={waypoints} courierPos={courierPos} height="100%" />
      </div>

      {!hasPosition && (
        <p className="border-t border-line px-4 py-2 text-xs text-content-faint">
          {delivery.status === 'ASSIGNED'
            ? 'Your courier is assigned and will start broadcasting shortly.'
            : delivery.status === 'ARRIVED'
              ? 'Your courier has arrived — show them your handover code.'
              : 'Waiting for the courier to start broadcasting.'}
        </p>
      )}
    </section>
  );
}
