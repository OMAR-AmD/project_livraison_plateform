'use client';

import { useEffect, useState } from 'react';
import Modal from './Modal';
import { clientGetCourierLocation } from '@/lib/api';
import dynamic from 'next/dynamic';
import { StatusPill } from '@/components/StatusPill';

// Leaflet touches `window`, so the map is never server-rendered.
const RouteMap = dynamic(() => import('./OptimizedRouteMap'), { ssr: false });

const POLL_MS = 2000;
const STALE_MS = 30000;

export default function TrackingModal({ isOpen, onClose, delivery }) {
  const [courierPos, setCourierPos] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [isStale, setIsStale] = useState(false);

  useEffect(() => {
    if (!isOpen || !delivery) return;

    let cancelled = false;

    const fetchLocation = () => {
      clientGetCourierLocation(delivery.id)
        .then((data) => {
          if (cancelled) return;
          if (data && data.latitude != null && data.longitude != null) {
            setCourierPos({ lat: data.latitude, lng: data.longitude });
            setLastUpdated(new Date());
            setIsStale(false);
          } else {
            // The key in Redis has expired, so the courier is genuinely not
            // broadcasting. Saying so beats leaving a frozen marker on screen.
            setIsStale(true);
          }
        })
        .catch(() => {
          if (!cancelled) setIsStale(true);
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
      setCourierPos(null);
      setLastUpdated(null);
      setIsStale(false);
    };
  }, [isOpen, delivery?.id]);

  if (!delivery) return null;

  const waypoints = [];
  if (delivery.pickupLat != null && delivery.pickupLng != null) {
    waypoints.push({
      latitude: delivery.pickupLat,
      longitude: delivery.pickupLng,
      type: 'PICKUP',
      description: delivery.pickupAddress || 'Pickup',
      step: 1,
    });
  }
  if (delivery.dropoffLat != null && delivery.dropoffLng != null) {
    waypoints.push({
      latitude: delivery.dropoffLat,
      longitude: delivery.dropoffLng,
      type: 'DROPOFF',
      description: delivery.dropoffAddress || 'Dropoff',
      step: 2,
    });
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Live tracking"
      description={delivery.description}
      size="lg"
    >
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-3">
          <StatusPill status={delivery.status} />

          <span
            className={`inline-flex items-center gap-2 text-xs font-medium ${
              isStale ? 'text-warn-400' : 'text-ok-400'
            }`}
          >
            <span className="relative flex h-2 w-2">
              {isStale ? (
                <span className="inline-flex h-2 w-2 rounded-full bg-warn-500" />
              ) : (
                <>
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-ok-400 opacity-75" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-ok-500" />
                </>
              )}
            </span>
            {isStale
              ? 'Signal lost — position is no longer being broadcast'
              : lastUpdated
                ? `Live · updated ${lastUpdated.toLocaleTimeString('en-GB')}`
                : 'Connecting…'}
          </span>
        </div>

        <div className="overflow-hidden rounded-lg border border-line">
          <RouteMap orderedWaypoints={waypoints} courierPos={courierPos} />
        </div>

        <dl className="grid gap-3 sm:grid-cols-2">
          <div className="rounded-md border border-line bg-surface-raised px-3.5 py-3">
            <dt className="text-[11px] font-semibold uppercase tracking-wider text-content-faint">
              Pickup
            </dt>
            <dd className="mt-1 text-sm text-content-soft">{delivery.pickupAddress}</dd>
          </div>
          <div className="rounded-md border border-line bg-surface-raised px-3.5 py-3">
            <dt className="text-[11px] font-semibold uppercase tracking-wider text-content-faint">
              Drop-off
            </dt>
            <dd className="mt-1 text-sm text-content-soft">{delivery.dropoffAddress}</dd>
          </div>
        </dl>
      </div>
    </Modal>
  );
}
