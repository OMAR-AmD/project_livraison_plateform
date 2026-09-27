'use client';

import { useState, useEffect } from 'react';
import { clientGetDeliveries, clientCreateDelivery, clientCancelDelivery, clientQuoteDelivery, clientPayDelivery } from '@/lib/api';
import Modal from '@/components/Modal';
import TrackingModal from '@/components/TrackingModal';
import RatingModal from '@/components/RatingModal';
import PaymentModal from '@/components/PaymentModal';
import LocationPicker from '@/components/LocationPicker';
import ChatWidget from '@/components/ChatWidget';
import { useToast } from '@/components/Toast';
import { clientRateDelivery, clientDeleteDelivery } from '@/lib/api';
import { StatusPill, PaymentTag } from '@/components/StatusPill';
import EmptyState, { EmptyIcons } from '@/components/EmptyState';

export default function ClientView() {
  const [deliveries, setDeliveries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isPaymentModalOpen, setIsPaymentModalOpen] = useState(false);
  const [trackingDelivery, setTrackingDelivery] = useState(null);
  const [ratingDelivery, setRatingDelivery] = useState(null);
  const [formData, setFormData] = useState({ description: '', pickupAddress: '', dropoffAddress: '', pickupLat: null, pickupLng: null });
  const [submitting, setSubmitting] = useState(false);
  const [gpsError, setGpsError] = useState('');
  const [quote, setQuote] = useState(null);
  const { addToast } = useToast();

  useEffect(() => {
    fetchDeliveries();
    const handleUpdate = () => {
      fetchDeliveries(true);
    };
    window.addEventListener('backendUpdated', handleUpdate);

    // Robust polling fallback for auto-rating in case SSE proxy drops
    const pollInterval = setInterval(() => {
      fetchDeliveries(true);
    }, 3000);

    return () => {
      window.removeEventListener('backendUpdated', handleUpdate);
      clearInterval(pollInterval);
    };
  }, []);

  const fetchDeliveries = async (isBackgroundUpdate = false) => {
    try {
      const data = await clientGetDeliveries();
      setDeliveries(data);
      
      // Auto-trigger rating modal if a delivery was just delivered
      if (isBackgroundUpdate) {
        const justDelivered = data.find(d => d.status === 'DELIVERED' && d.rating == null);
        if (justDelivered) {
          setRatingDelivery(prev => prev ? prev : justDelivered);
        }
      }
    } catch (err) {
      addToast('Failed to load deliveries', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleProceedToPayment = async (e) => {
    e.preventDefault();
    if (!formData.pickupLat || !formData.pickupLng || !formData.dropoffLat || !formData.dropoffLng) {
      addToast('Please select valid pickup and dropoff locations', 'error');
      return;
    }

    setSubmitting(true);
    try {
      // Ask the server to price this route. This replaces both the browser-side
      // haversine estimate and the direct fetch to http://localhost:5000 that used
      // to run the 45-minute check — the browser no longer talks to OSRM at all.
      const quoted = await clientQuoteDelivery(formData);

      if (quoted.exceedsTimeLimit) {
        const minutes = Math.round(quoted.durationSeconds / 60);
        const limit = Math.round(quoted.maxDurationSeconds / 60);
        addToast(`This route takes about ${minutes} min, beyond our ${limit} min limit. Please choose closer locations.`, 'error');
        return;
      }

      setQuote(quoted);
      setIsModalOpen(false);
      setIsPaymentModalOpen(true);
    } catch (err) {
      addToast(err.message || 'Unable to price this delivery', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleFinalizeCreation = async () => {
    setIsPaymentModalOpen(false);
    setSubmitting(true);
    try {
      // Two steps on purpose: the order is created unpaid, then payment is captured.
      // The order only becomes revenue — and only reaches the auto-dispatcher — once
      // the capture succeeds, so a failed checkout cannot occupy a courier.
      const created = await clientCreateDelivery(formData);
      await clientPayDelivery(created.id);

      addToast('Payment successful! Delivery created.', 'success');
      setFormData({ description: '', pickupAddress: '', dropoffAddress: '', pickupLat: null, pickupLng: null });
      setGpsError('');
      setQuote(null);
      fetchDeliveries();
    } catch (err) {
      addToast(err.message || 'Payment could not be completed', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const getGPS = () => {
    setGpsError('');
    if (!navigator.geolocation) {
      setGpsError('Geolocation not supported');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setFormData(prev => ({
          ...prev,
          pickupLat: pos.coords.latitude,
          pickupLng: pos.coords.longitude
        }));
        addToast('GPS location acquired!', 'success');
      },
      (err) => {
        setGpsError('GPS unavailable - manual address only');
        console.warn('GPS error:', err);
      },
      { enableHighAccuracy: true }
    );
  };

  const handleCancel = async (id) => {
    if (!confirm('Are you sure you want to cancel this delivery?')) return;
    try {
      await clientCancelDelivery(id);
      addToast('Delivery cancelled', 'success');
      fetchDeliveries();
    } catch (err) {
      addToast(err.message, 'error');
    }
  };

  const handleDelete = async (id) => {
    if (!confirm('Are you sure you want to delete this delivery from your history?')) return;
    try {
      await clientDeleteDelivery(id);
      addToast('Delivery deleted', 'success');
      fetchDeliveries();
    } catch (err) {
      addToast(err.message || 'Failed to delete delivery', 'error');
    }
  };

  const handleRateSubmit = async (deliveryId, rating, comment) => {
    try {
      await clientRateDelivery(deliveryId, rating, comment);
      addToast('Thank you for your rating!', 'success');
      setRatingDelivery(null);
      fetchDeliveries();
    } catch (err) {
      addToast(err.message || 'Failed to submit rating', 'error');
    }
  };

  return (
    <div className="space-y-6">
      <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="page-title">My deliveries</h1>
          <p className="mt-1 text-sm text-content-muted">
            {deliveries.length === 0
              ? 'Nothing booked yet.'
              : `${deliveries.length} order${deliveries.length === 1 ? '' : 's'}`}
          </p>
        </div>
        <button onClick={() => setIsModalOpen(true)} className="btn-primary shrink-0">
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          New delivery
        </button>
      </header>

      <div className="surface overflow-hidden">
        {loading ? (
          <div className="space-y-3 p-4">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-14 animate-pulse rounded-md bg-surface-raised" />
            ))}
          </div>
        ) : deliveries.length === 0 ? (
          <EmptyState
            icon={EmptyIcons.box}
            title="No deliveries yet"
            body="Book your first delivery and you will be able to follow the courier live on a map."
            action={
              <button onClick={() => setIsModalOpen(true)} className="btn-primary">
                Book a delivery
              </button>
            }
          />
        ) : (
          <>
            {/* ── Desktop table ─────────────────────────────────────── */}
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full border-collapse text-left">
                <thead>
                  <tr>
                    <th className="table-head">Order</th>
                    <th className="table-head">Route</th>
                    <th className="table-head">Status</th>
                    <th className="table-head text-right">Price</th>
                    <th className="table-head">Booked</th>
                    <th className="table-head text-right">Actions</th>
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
                      <td className="table-cell whitespace-nowrap text-content-faint">
                        {new Date(d.createdAt).toLocaleDateString('en-GB', {
                          day: '2-digit',
                          month: 'short',
                          year: 'numeric',
                        })}
                      </td>
                      <td className="table-cell">
                        <div className="flex items-center justify-end gap-2">
                          {d.status === 'IN_TRANSIT' && (
                            <button onClick={() => setTrackingDelivery(d)} className="btn-secondary btn-sm">
                              Track
                            </button>
                          )}
                          {d.status === 'DELIVERED' && d.rating == null && (
                            <button onClick={() => setRatingDelivery(d)} className="btn-primary btn-sm">
                              Rate
                            </button>
                          )}
                          {d.status === 'DELIVERED' && d.rating != null && (
                            <span className="inline-flex items-center gap-1 text-xs font-semibold text-ok-400">
                              <svg className="h-3.5 w-3.5" viewBox="0 0 20 20" fill="currentColor">
                                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                              </svg>
                              {d.rating}/5
                            </span>
                          )}
                          {d.status === 'PENDING' && (
                            <button
                              onClick={() => handleCancel(d.id)}
                              className="btn-ghost btn-sm text-danger-400 hover:text-danger-300"
                            >
                              Cancel
                            </button>
                          )}
                          {(d.status === 'DELIVERED' || d.status === 'CANCELLED') && (
                            <button
                              onClick={() => handleDelete(d.id)}
                              className="btn-ghost btn-sm text-content-faint hover:text-danger-400"
                              aria-label="Delete order"
                            >
                              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                                <path strokeLinecap="round" strokeLinejoin="round" d="M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0" />
                              </svg>
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* ── Mobile cards ──────────────────────────────────────── */}
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
                    <p className="shrink-0 text-right font-semibold text-content tabular">
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
                    <span className="ml-auto text-xs text-content-faint">
                      {new Date(d.createdAt).toLocaleDateString('en-GB', {
                        day: '2-digit',
                        month: 'short',
                      })}
                    </span>
                  </div>

                  <div className="mt-3 flex flex-wrap gap-2">
                    {d.status === 'IN_TRANSIT' && (
                      <button onClick={() => setTrackingDelivery(d)} className="btn-secondary btn-sm flex-1">
                        Track
                      </button>
                    )}
                    {d.status === 'DELIVERED' && d.rating == null && (
                      <button onClick={() => setRatingDelivery(d)} className="btn-primary btn-sm flex-1">
                        Rate delivery
                      </button>
                    )}
                    {d.status === 'PENDING' && (
                      <button onClick={() => handleCancel(d.id)} className="btn-danger btn-sm flex-1">
                        Cancel order
                      </button>
                    )}
                    {(d.status === 'DELIVERED' || d.status === 'CANCELLED') && (
                      <button onClick={() => handleDelete(d.id)} className="btn-ghost btn-sm flex-1">
                        Delete
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>

      <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} title="New delivery request">
        <form onSubmit={handleProceedToPayment} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-content-soft mb-1">Package description</label>
            <input 
              type="text" required className="input" placeholder="e.g. 2 small boxes"
              value={formData.description} onChange={e => setFormData({...formData, description: e.target.value})}
            />
          </div>
          <div className="relative z-20">
            <LocationPicker 
              label="Pickup location" 
              placeholder="Search or click the map"
              address={formData.pickupAddress}
              lat={formData.pickupLat}
              lng={formData.pickupLng}
              onLocationChange={(loc) => {
                setFormData(prev => ({
                  ...prev,
                  pickupAddress: loc.address,
                  pickupLat: loc.lat,
                  pickupLng: loc.lng
                }));
              }}
            />
            <div className="flex justify-end mt-2">
              <button type="button" onClick={getGPS} className="text-xs px-2 py-1 bg-info-500/20 text-info-400 border border-info-500/30 rounded hover:bg-info-500/30 transition-colors">
                📍 Use my GPS location
              </button>
            </div>
            {gpsError && <p className="text-xs text-danger-400 mt-1">{gpsError}</p>}
          </div>
          
          <div className="relative z-10">
            <LocationPicker 
              label="Dropoff location" 
              placeholder="Search or click the map"
              address={formData.dropoffAddress}
              lat={formData.dropoffLat}
              lng={formData.dropoffLng}
              onLocationChange={(loc) => {
                setFormData(prev => ({
                  ...prev,
                  dropoffAddress: loc.address,
                  dropoffLat: loc.lat,
                  dropoffLng: loc.lng
                }));
              }}
            />
          </div>
          <div className="pt-4 flex gap-3">
            <button type="button" onClick={() => setIsModalOpen(false)} className="btn-secondary flex-1">Close</button>
            <button type="submit" disabled={submitting} className="btn-primary flex-1">
              Continue to payment
            </button>
          </div>
        </form>
      </Modal>
      
      <PaymentModal 
        isOpen={isPaymentModalOpen}
        onClose={() => {
          setIsPaymentModalOpen(false);
          setIsModalOpen(true); // Return to form
        }}
        amount={quote ? quote.price : 0}
        quote={quote}
        onSuccess={handleFinalizeCreation}
      />

      <TrackingModal 
        isOpen={!!trackingDelivery} 
        onClose={() => setTrackingDelivery(null)} 
        delivery={trackingDelivery} 
      />
      <RatingModal
        isOpen={!!ratingDelivery}
        onClose={() => setRatingDelivery(null)}
        deliveryId={ratingDelivery?.id}
        onSubmit={handleRateSubmit}
      />
      
      <ChatWidget />
    </div>
  );
}
