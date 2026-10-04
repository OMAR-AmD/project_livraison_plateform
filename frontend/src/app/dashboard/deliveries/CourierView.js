'use client';

import { useState, useEffect, useRef } from 'react';
import { courierGetDeliveries, courierGetStats, courierUpdateStatus, courierSendLocation, courierOptimizeRoute } from '@/lib/api';
import { useToast } from '@/components/Toast';
import OptimizedRouteModal from '@/components/OptimizedRouteModal';
import { StatusPill, PaymentTag } from '@/components/StatusPill';
import CourierStats from '@/components/CourierStats';
import StopAction from '@/components/StopAction';
import ScanHandoverModal from '@/components/ScanHandoverModal';
import EmptyState, { EmptyIcons } from '@/components/EmptyState';
import { defaultToSimulation, detectGpsEnvironment } from '@/lib/gpsMode';

export default function CourierView() {
  const [deliveries, setDeliveries] = useState([]);
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [isSimulating, setIsSimulating] = useState(false);
  // Once the courier picks a mode themselves we stop auto-selecting for them.
  const gpsModeChosenRef = useRef(false);
  const gpsErrorNotifiedRef = useRef(false);
  const { addToast } = useToast();
  const watchIdRef = useRef(null);
  const simIntervalRef = useRef(null);
  const [simPath, setSimPath] = useState(null);
  const simIndexRef = useRef(0);
  const [currentCoords, setCurrentCoords] = useState({ lat: 33.5731, lng: -7.5898 });
  const [isRouteModalOpen, setIsRouteModalOpen] = useState(false);
  // Held while we broadcast real GPS so the phone screen does not sleep and
  // silence the position stream.
  const wakeLockRef = useRef(null);
  const hasInTransit = deliveries.some((d) => d.status === 'IN_TRANSIT');

  useEffect(() => {
    fetchDeliveries();
    fetchStats();
  }, []);

  // Pick a sensible mode whenever a round starts: a phone on HTTPS shares its
  // real position, while the laptop (and the automated rehearsal) gets the
  // simulated route. A manual choice always wins over this default.
  useEffect(() => {
    if (gpsModeChosenRef.current) return;
    const hasInTransit = deliveries.some(d => d.status === 'IN_TRANSIT');
    setIsSimulating(hasInTransit ? defaultToSimulation(detectGpsEnvironment()) : false);
  }, [deliveries]);

  // Fetch optimal path when simulation starts
  useEffect(() => {
    if (isSimulating) {
      const generatePath = async () => {
        try {
          let startLat = currentCoords.lat;
          let startLng = currentCoords.lng;

          // Find an active delivery to spawn near its pickup location
          const activeDelivery = deliveries.find(d => d.status === 'IN_TRANSIT' || d.status === 'ASSIGNED');
          if (activeDelivery && activeDelivery.pickupLat && activeDelivery.pickupLng) {
            startLat = activeDelivery.pickupLat - 0.005; // slight offset (~500m)
            startLng = activeDelivery.pickupLng - 0.005;
            setCurrentCoords({ lat: startLat, lng: startLng });
          }

          const optData = await courierOptimizeRoute(startLat, startLng);
          if (!optData || !optData.orderedWaypoints || optData.orderedWaypoints.length === 0) {
            addToast('No deliveries to simulate', 'warning');
            return;
          }
          
          let coordsString = `${startLng},${startLat};`;
          coordsString += optData.orderedWaypoints.map(wp => `${wp.longitude},${wp.latitude}`).join(';');
          
          const osrmRes = await fetch(`/osrm/route/v1/driving/${coordsString}?overview=full&geometries=geojson`);
          const osrmData = await osrmRes.json();
          
          if (osrmData.routes && osrmData.routes.length > 0) {
            const path = osrmData.routes[0].geometry.coordinates.map(c => ({ lat: c[1], lng: c[0] }));
            setSimPath(path);
            simIndexRef.current = 0;
            addToast('Simulation path generated!', 'success');
          }
        } catch (err) {
          console.error("Simulation path error:", err);
        }
      };
      generatePath();
    } else {
      setSimPath(null);
      if (simIntervalRef.current) clearInterval(simIntervalRef.current);
    }
  }, [isSimulating]);

  useEffect(() => {
    const inTransitDeliveries = deliveries.filter(d => d.status === 'IN_TRANSIT');
    
    const cleanup = () => {
      if (watchIdRef.current !== null && navigator.geolocation) {
        navigator.geolocation.clearWatch(watchIdRef.current);
        watchIdRef.current = null;
      }
      if (simIntervalRef.current !== null) {
        clearInterval(simIntervalRef.current);
        simIntervalRef.current = null;
      }
    };

    if (inTransitDeliveries.length > 0) {
      if (isSimulating) {
        // --- SIMULATION MODE ---
        if (watchIdRef.current !== null && navigator.geolocation) {
          navigator.geolocation.clearWatch(watchIdRef.current);
          watchIdRef.current = null;
        }

        if (simPath && simPath.length > 0 && simIntervalRef.current === null) {
          simIntervalRef.current = setInterval(() => {
            setCurrentCoords(prev => {
              if (simIndexRef.current >= simPath.length) {
                clearInterval(simIntervalRef.current);
                simIntervalRef.current = null;
                addToast('Simulation reached destination!', 'success');
                
                // Auto-complete deliveries
                inTransitDeliveries.forEach(d => {
                  handleStatusChange(d.id, 'DELIVERED');
                });
                return prev;
              }
              
              const nextPoint = simPath[simIndexRef.current];
              
              // Dynamic Traffic Simulation
              const hour = new Date().getHours();
              const isRushHour = (hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 19);
              const speedStep = isRushHour ? 1 : 3; // Slow down (1 point) in rush hour, faster (3 points) otherwise
              
              simIndexRef.current += speedStep;
              
              // Ensure we don't skip the last point
              if (simIndexRef.current > simPath.length) {
                simIndexRef.current = simPath.length;
              }
              inTransitDeliveries.forEach(d => {
                courierSendLocation(d.id, nextPoint.lat, nextPoint.lng)
                  .catch(err => console.warn('Mock Location broadcast failed:', err));
              });
              
              return nextPoint;
            });
          }, 1000); // 1 second ticks
        }
      } else {
        // --- REAL GPS MODE ---
        if (simIntervalRef.current !== null) {
          clearInterval(simIntervalRef.current);
          simIntervalRef.current = null;
        }

        if (watchIdRef.current === null && navigator.geolocation) {
          watchIdRef.current = navigator.geolocation.watchPosition(
            ({ coords }) => {
              setCurrentCoords({ lat: coords.latitude, lng: coords.longitude });
              inTransitDeliveries.forEach(d => {
                courierSendLocation(d.id, coords.latitude, coords.longitude)
                  .catch(err => console.warn('Location broadcast failed:', err));
              });
            },
            (err) => {
              console.error('GPS error', err);
              if (!gpsErrorNotifiedRef.current) {
                gpsErrorNotifiedRef.current = true;
                addToast('Real GPS unavailable — allow location, or use the simulator.', 'warning');
              }
            },
            { enableHighAccuracy: true, maximumAge: 5000 }
          );
        }
      }
    } else {
      cleanup();
    }

    return cleanup;
  }, [deliveries, isSimulating, simPath]);

  // A phone suspends geolocation (and timers) when its screen turns off or the
  // tab is hidden, which is what made the client fall to "Signal lost" in the
  // middle of a delivery. Hold a screen wake lock while real GPS is being
  // broadcast so the phone stays awake, and release it when we stop or switch
  // back to the simulator. The lock is best-effort: a browser without the API
  // simply keeps its previous behaviour.
  useEffect(() => {
    const broadcasting = !isSimulating && hasInTransit;

    if (!broadcasting || typeof navigator === 'undefined' || !('wakeLock' in navigator)) {
      if (wakeLockRef.current) {
        wakeLockRef.current.release().catch(() => {});
        wakeLockRef.current = null;
      }
      return;
    }

    let active = true;
    const acquire = async () => {
      if (!active || wakeLockRef.current) return;
      try {
        const lock = await navigator.wakeLock.request('screen');
        if (!active) {
          lock.release().catch(() => {});
          return;
        }
        wakeLockRef.current = lock;
        // The browser drops the lock when the page is hidden; clearing our
        // reference lets the visibility handler request a fresh one.
        lock.addEventListener('release', () => {
          if (wakeLockRef.current === lock) wakeLockRef.current = null;
        });
      } catch (err) {
        console.warn('Screen wake lock unavailable:', err);
      }
    };

    acquire();
    const onVisibility = () => {
      if (document.visibilityState === 'visible') acquire();
    };
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      active = false;
      document.removeEventListener('visibilitychange', onVisibility);
      if (wakeLockRef.current) {
        wakeLockRef.current.release().catch(() => {});
        wakeLockRef.current = null;
      }
    };
  }, [isSimulating, hasInTransit]);

  const fetchDeliveries = async () => {
    try {
      const data = await courierGetDeliveries();
      setDeliveries(data);
    } catch (err) {
      addToast('Failed to load deliveries', 'error');
    } finally {
      setLoading(false);
    }
  };

  /**
   * The track record is a second request, deliberately not folded into
   * fetchDeliveries: if it fails, the courier should still get their round.
   * A wrong-looking counter is a far smaller problem than an empty work list.
   */
  const fetchStats = async () => {
    try {
      setStats(await courierGetStats());
    } catch (err) {
      console.warn('Courier stats unavailable:', err);
    }
  };

  const handleStatusChange = async (id, newStatus, extra = {}) => {
    try {
      await courierUpdateStatus(id, newStatus, extra);
      addToast('Status updated', 'success');
      fetchDeliveries();
      // Completing a stop is what moves the counters, so refresh them too.
      fetchStats();
    } catch (err) {
      addToast(err.message, 'error');
    }
  };

  // Handover-code confirm (Voie A): sealing by scanned/typed code instead of
  // GPS proximity. Errors stay inside the scan modal so the courier can retry
  // the digits; success closes it and refreshes like any status change.
  const [scanDelivery, setScanDelivery] = useState(null);
  const handleScanConfirm = async (code) => {
    try {
      await courierUpdateStatus(scanDelivery.id, 'DELIVERED', { handoverCode: code });
      addToast('Delivery confirmed by handover code', 'success');
      setScanDelivery(null);
      fetchDeliveries();
      fetchStats();
    } catch (err) {
      throw new Error(err.message || 'Could not confirm with this code.');
    }
  };

  /**
   * The courier can override the auto-selected mode. Choosing real GPS on a
   * non-HTTPS origin cannot work (browsers require a secure context for
   * geolocation), so we warn before handing the browser a broken promise.
   */
  const toggleGpsMode = () => {
    const goingReal = isSimulating;
    gpsModeChosenRef.current = true;
    if (goingReal && !detectGpsEnvironment().secureContext) {
      addToast('Real GPS needs HTTPS. Open the deployed site, or keep the simulator.', 'warning');
    }
    setIsSimulating((prev) => !prev);
  };

  return (
    <div className="space-y-6">
      <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="page-title">Assigned round</h1>
          <p className="mt-1 text-sm text-content-muted">
            {deliveries.length === 0
              ? 'Nothing assigned to you.'
              : `${deliveries.length} stop${deliveries.length === 1 ? '' : 's'} on your round`}
          </p>
          {hasInTransit && !isSimulating && (
            <p className="mt-1 text-xs text-content-faint">
              Broadcasting your real GPS — the screen is kept awake so the position keeps flowing.
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {deliveries.some((d) => d.status === 'IN_TRANSIT') && (
            <span className="inline-flex items-center gap-2 rounded-md border border-signal-500/25 bg-signal-500/10 px-3 py-2 text-xs font-semibold text-signal-400">
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-signal-400 opacity-75" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-signal-500" />
              </span>
              {isSimulating ? 'Simulating GPS' : 'Sharing live location'}
            </span>
          )}
          {deliveries.some((d) => d.status === 'IN_TRANSIT') && (
            <button
              onClick={toggleGpsMode}
              className="btn-secondary shrink-0"
              title={
                isSimulating
                  ? 'Broadcast your real phone position instead of a simulated route'
                  : 'Play a simulated route instead of your real position'
              }
            >
              {isSimulating ? 'Use my real GPS' : 'Simulate route'}
            </button>
          )}
          <button onClick={() => setIsRouteModalOpen(true)} className="btn-secondary shrink-0">
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
            </svg>
            Optimise route
          </button>
        </div>
      </header>

      <CourierStats stats={stats} />

      <div className="surface overflow-hidden">
        {loading ? (
          <div className="space-y-3 p-4">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-14 animate-pulse rounded-md bg-surface-raised" />
            ))}
          </div>
        ) : deliveries.length === 0 ? (
          <EmptyState
            icon={EmptyIcons.truck}
            title="No parcels assigned"
            body="When the dispatcher assigns a paid order to you it will appear here, along with the optimised route."
          />
        ) : (
          <>
            {/* Desktop table */}
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full border-collapse text-left">
                <thead>
                  <tr>
                    <th className="table-head">Stop</th>
                    <th className="table-head">Route</th>
                    <th className="table-head">Status</th>
                    <th className="table-head text-right">Fare</th>
                    <th className="table-head text-right">Next step</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {deliveries.map((d) => (
                    <tr key={d.id} className="transition-colors hover:bg-surface-raised">
                      <td className="table-cell">
                        <p className="font-semibold text-content">{d.description}</p>
                        <p className="mt-0.5 font-mono text-xs text-content-faint">
                          {d.id.slice(0, 8)}
                        </p>
                      </td>
                      <td className="table-cell">
                        <div className="flex items-center gap-2 text-content-muted">
                          <span className="max-w-[11rem] truncate" title={d.pickupAddress}>
                            {d.pickupAddress}
                          </span>
                          <svg className="h-3.5 w-3.5 shrink-0 text-content-faint" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                            <path strokeLinecap="round" strokeLinejoin="round" d="M13.5 4.5L21 12m0 0l-7.5 7.5M21 12H3" />
                          </svg>
                          <span className="max-w-[11rem] truncate" title={d.dropoffAddress}>
                            {d.dropoffAddress}
                          </span>
                        </div>
                      </td>
                      <td className="table-cell">
                        <StatusPill status={d.status} />
                        <div className="mt-1">
                          <PaymentTag paymentStatus={d.paymentStatus} />
                        </div>
                      </td>
                      <td className="table-cell text-right font-semibold text-content tabular">
                        {d.price != null ? `${d.price.toFixed(2)} MAD` : '—'}
                      </td>
                      <td className="table-cell">
                        <div className="flex items-center justify-end gap-2">
                          {d.status === 'IN_TRANSIT' && (
                            <button onClick={() => setScanDelivery(d)} className="btn-secondary btn-sm">
                              Scan code
                            </button>
                          )}
                          <StopAction delivery={d} onChange={handleStatusChange} />
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Mobile cards */}
            <ul className="divide-y divide-line md:hidden">
              {deliveries.map((d) => (
                <li key={d.id} className="p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate font-semibold text-content">{d.description}</p>
                      <p className="mt-0.5 font-mono text-xs text-content-faint">
                        {d.id.slice(0, 8)}
                      </p>
                    </div>
                    <p className="shrink-0 font-semibold text-content tabular">
                      {d.price != null ? `${d.price.toFixed(0)} MAD` : '—'}
                    </p>
                  </div>

                  <div className="mt-3 space-y-1 text-sm text-content-muted">
                    <p className="truncate">
                      <span className="text-content-faint">From </span>
                      {d.pickupAddress}
                    </p>
                    <p className="truncate">
                      <span className="text-content-faint">To </span>
                      {d.dropoffAddress}
                    </p>
                  </div>

                  <div className="mt-3 flex flex-wrap items-center gap-3">
                    <StatusPill status={d.status} />
                    <PaymentTag paymentStatus={d.paymentStatus} />
                  </div>

                  <div className="mt-3 flex gap-2">
                    {d.status === 'IN_TRANSIT' && (
                      <button onClick={() => setScanDelivery(d)} className="btn-secondary btn-sm flex-1">
                        Scan code
                      </button>
                    )}
                    <StopAction delivery={d} onChange={handleStatusChange} block />
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>

      <OptimizedRouteModal 
        isOpen={isRouteModalOpen}
        onClose={() => setIsRouteModalOpen(false)}
        courierPos={currentCoords}
      />
      {scanDelivery && (
        <ScanHandoverModal
          delivery={scanDelivery}
          onConfirm={handleScanConfirm}
          onClose={() => setScanDelivery(null)}
        />
      )}
    </div>
  );
}
