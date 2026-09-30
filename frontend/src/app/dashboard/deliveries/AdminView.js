'use client';

import { useState, useEffect } from 'react';
import { adminGetDeliveries, adminGetCouriers, adminAssignCourier, adminDeleteDelivery, adminUpdateDeliveryStatus, adminUnassignCourier } from '@/lib/api';
import Modal from '@/components/Modal';
import { useToast } from '@/components/Toast';
import dynamic from 'next/dynamic';

const AdminMap = dynamic(() => import('@/components/AdminMap'), {
  ssr: false,
});

import { StatusPill, PaymentTag } from '@/components/StatusPill';
import EmptyState, { EmptyIcons } from '@/components/EmptyState';

export default function AdminView() {
  const [deliveries, setDeliveries] = useState([]);
  const [couriers, setCouriers] = useState([]);
  const [loading, setLoading] = useState(true);
  
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [selectedDelivery, setSelectedDelivery] = useState(null);
  const [selectedCourier, setSelectedCourier] = useState('');
  const [submitting, setSubmitting] = useState(false);
  
  const { addToast } = useToast();

  useEffect(() => {
    fetchData();

    const handleUpdate = () => {
      fetchData();
    };
    window.addEventListener('backendUpdated', handleUpdate);

    const pollInterval = setInterval(() => {
      fetchData();
    }, 10000);

    return () => {
      window.removeEventListener('backendUpdated', handleUpdate);
      clearInterval(pollInterval);
    };
  }, []);

  const fetchData = async () => {
    try {
      const [delivData, courierData] = await Promise.all([
        adminGetDeliveries(),
        adminGetCouriers()
      ]);
      setDeliveries(delivData);
      setCouriers(courierData);
    } catch (err) {
      addToast('Failed to load data', 'error');
    } finally {
      setLoading(false);
    }
  };

  const openAssignModal = (delivery) => {
    setSelectedDelivery(delivery);
    // Preselect the current courier so an accidental submit is a no-op rather
    // than an unassign in disguise.
    setSelectedCourier(delivery.courierId || '');
    setIsModalOpen(true);
  };

  const handleAssign = async (e) => {
    e.preventDefault();
    if (!selectedCourier) {
      addToast('Please select a courier', 'error');
      return;
    }
    setSubmitting(true);
    try {
      const updated = await adminAssignCourier(selectedDelivery.id, selectedCourier);
      addToast(`Assigned to ${updated.courierEmail}`, 'success');
      setIsModalOpen(false);
      fetchData();
    } catch (err) {
      addToast(err.message, 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleUnassign = async (delivery) => {
    if (!confirm(`Take "${delivery.description}" off ${delivery.courierEmail}? The order goes back to the unassigned pool.`)) return;
    try {
      await adminUnassignCourier(delivery.id);
      addToast('Courier removed, order is unassigned', 'success');
      fetchData();
    } catch (err) {
      addToast(err.message || 'Failed to unassign', 'error');
    }
  };

  /** How many live stops each courier already has, for the picker. */
  const courierLoad = (courierId) =>
    deliveries.filter(
      (d) =>
        d.courierId === courierId &&
        (d.status === 'ASSIGNED' || d.status === 'IN_TRANSIT')
    ).length;

  const handleDelete = async (deliveryId) => {
    if (!confirm('Are you sure you want to delete this delivery?')) return;
    try {
      await adminDeleteDelivery(deliveryId);
      addToast('Delivery deleted successfully', 'success');
      fetchData();
    } catch (err) {
      addToast(err.message || 'Failed to delete delivery', 'error');
    }
  };

  const handleStatusOverride = async (delivery, newStatus) => {
    if (!newStatus || newStatus === delivery.status) return;
    if (!confirm(`Change delivery status from ${delivery.status} to ${newStatus}? Both parties will be notified.`)) return;
    try {
      await adminUpdateDeliveryStatus(delivery.id, newStatus);
      addToast(`Status changed to ${newStatus}`, 'success');
      fetchData();
    } catch (err) {
      addToast(err.message || 'Failed to change status', 'error');
    }
  };

  return (
    <div className="space-y-6">
      <header>
        <h1 className="page-title">Fleet overview</h1>
        <p className="mt-1 text-sm text-content-muted">
          {deliveries.length === 0
            ? 'No deliveries recorded.'
            : `${deliveries.length} order${deliveries.length === 1 ? '' : 's'} across all couriers`}
        </p>
      </header>

      <AdminMap deliveries={deliveries} />

      <section>
        <h2 className="section-title">All deliveries</h2>

        <div className="surface mt-3 overflow-hidden">
          {loading ? (
            <div className="space-y-3 p-4">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-12 animate-pulse rounded-md bg-surface-raised" />
              ))}
            </div>
          ) : deliveries.length === 0 ? (
            <EmptyState
              icon={EmptyIcons.box}
              title="No deliveries in the system"
              body="Orders booked by clients will appear here, along with the courier the dispatcher assigned."
            />
          ) : (
            <>
              {/* Desktop table */}
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full border-collapse text-left">
                  <thead>
                    <tr>
                      <th className="table-head">Reference</th>
                      <th className="table-head">Client</th>
                      <th className="table-head">Courier</th>
                      <th className="table-head">Status</th>
                      <th className="table-head text-right">Fare</th>
                      <th className="table-head text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {deliveries.map((d) => (
                      <tr key={d.id} className="transition-colors hover:bg-surface-raised">
                        <td className="table-cell">
                          <p className="font-mono text-xs text-content-soft">
                            {d.id.slice(0, 8)}
                          </p>
                          <p className="mt-0.5 text-xs text-content-faint">
                            {d.description}
                          </p>
                        </td>
                        <td className="table-cell max-w-[14rem] truncate">
                          {d.clientEmail}
                        </td>
                        <td className="table-cell max-w-[14rem] truncate">
                          {d.courierEmail ? (
                            <span className="text-content-soft">{d.courierEmail}</span>
                          ) : (
                            <span className="text-content-faint">Unassigned</span>
                          )}
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
                            <button
                              onClick={() => openAssignModal(d)}
                              className="btn-secondary btn-sm"
                              aria-label={`Assign courier to ${d.description}`}
                            >
                              {d.courierEmail ? 'Reassign' : 'Assign'}
                            </button>
                            {d.courierEmail && (
                              <button
                                onClick={() => handleUnassign(d)}
                                className="btn-ghost btn-sm text-content-faint hover:text-danger-400"
                                aria-label={`Unassign ${d.description}`}
                                title="Return to the unassigned pool"
                              >
                                Unassign
                              </button>
                            )}
                            <select
                              aria-label={`Change status of ${d.description}`}
                              value={d.status}
                              onChange={(e) => handleStatusOverride(d, e.target.value)}
                              className="input btn-sm max-w-[9rem] cursor-pointer"
                            >
                              {['PENDING', 'ASSIGNED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'].map((s) => (
                                <option key={s} value={s}>{s}</option>
                              ))}
                            </select>
                            <button
                              onClick={() => handleDelete(d.id)}
                              className="btn-ghost btn-sm text-content-faint hover:text-danger-400"
                              aria-label="Delete delivery"
                            >
                                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                                  <path strokeLinecap="round" strokeLinejoin="round" d="M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0" />
                                </svg>
                              </button>
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

                    <dl className="mt-3 space-y-1 text-sm">
                      <div className="flex gap-2">
                        <dt className="w-16 shrink-0 text-content-faint">Client</dt>
                        <dd className="min-w-0 truncate text-content-soft">{d.clientEmail}</dd>
                      </div>
                      <div className="flex gap-2">
                        <dt className="w-16 shrink-0 text-content-faint">Courier</dt>
                        <dd className="min-w-0 truncate text-content-soft">
                          {d.courierEmail || 'Unassigned'}
                        </dd>
                      </div>
                    </dl>

                    <div className="mt-3 flex flex-wrap items-center gap-3">
                      <StatusPill status={d.status} />
                      <PaymentTag paymentStatus={d.paymentStatus} />
                    </div>

                    <div className="mt-3 flex flex-wrap gap-2">
                      <button
                        onClick={() => openAssignModal(d)}
                        className="btn-secondary btn-sm flex-1"
                        aria-label={`Assign courier to ${d.description}`}
                      >
                        {d.courierEmail ? 'Reassign' : 'Assign courier'}
                      </button>
                      {d.courierEmail && (
                        <button
                          onClick={() => handleUnassign(d)}
                          className="btn-ghost btn-sm flex-1"
                          aria-label={`Unassign ${d.description}`}
                        >
                          Unassign
                        </button>
                      )}
                      <select
                        aria-label={`Change status of ${d.description}`}
                        value={d.status}
                        onChange={(e) => handleStatusOverride(d, e.target.value)}
                        className="input btn-sm flex-1 cursor-pointer"
                      >
                        {['PENDING', 'ASSIGNED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'].map((s) => (
                          <option key={s} value={s}>{s}</option>
                        ))}
                      </select>
                      <button onClick={() => handleDelete(d.id)} className="btn-ghost btn-sm flex-1">
                        Delete
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </section>

      <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} title="Assign courier">
        <form onSubmit={handleAssign} className="space-y-5">
          {selectedDelivery && (
            <div className="rounded-md border border-line bg-surface-raised px-3.5 py-3">
              <p className="font-semibold text-content">{selectedDelivery.description}</p>
              <p className="mt-1 text-xs text-content-muted">
                Currently{' '}
                <span className="text-content-soft">
                  {selectedDelivery.courierEmail || 'unassigned'}
                </span>{' '}
                · status <span className="text-content-soft">{selectedDelivery.status}</span>
              </p>
            </div>
          )}
          <div>
            <label htmlFor="assign-courier" className="label">
              Courier
            </label>
            <select
              id="assign-courier"
              className="input"
              value={selectedCourier}
              onChange={(e) => setSelectedCourier(e.target.value)}
              required
            >
              <option value="" disabled>
                Select a courier…
              </option>
              {couriers.map((c) => {
                // The live stop count, so the dispatcher can see who is already
                // loaded instead of assigning blind.
                const load = courierLoad(c.id);
                const isCurrent = selectedDelivery && selectedDelivery.courierId === c.id;
                return (
                  <option key={c.id} value={c.id} disabled={isCurrent}>
                    {c.email} — {load} active stop{load === 1 ? '' : 's'}
                    {isCurrent ? ' (current)' : ''}
                  </option>
                );
              })}
            </select>
            <p className="mt-2 text-xs text-content-faint">
              Assigning overrides the automatic choice. The status is reset to{' '}
              <span className="text-content-soft">ASSIGNED</span> so the new courier has a stop to
              work, and both couriers are notified.
            </p>
          </div>
          <div className="flex flex-col-reverse gap-2 border-t border-line pt-5 sm:flex-row sm:justify-end">
            <button type="button" onClick={() => setIsModalOpen(false)} className="btn-secondary">
              Cancel
            </button>
            <button type="submit" disabled={submitting} className="btn-primary">
              {submitting ? 'Assigning…' : 'Assign courier'}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
