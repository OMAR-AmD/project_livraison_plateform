'use client';

/**
 * The single source of truth for how a delivery state is rendered.
 *
 * This was previously duplicated in all three role views, which meant a change
 * to the status vocabulary had to be made in three places and the views could
 * silently drift apart. One state, one colour, everywhere:
 *
 *   PENDING     neutral   — accepted, not yet assigned
 *   ASSIGNED    info      — a courier has it
 *   IN_TRANSIT  accent    — the one state the operator is watching
 *   ARRIVED     accent    — at the door, handover code armed
 *   DELIVERED   success   — done
 *   CANCELLED   danger    — dead
 */
const STATES = {
  PENDING: { label: 'Pending', className: 'status-pending' },
  ASSIGNED: { label: 'Assigned', className: 'status-assigned' },
  IN_TRANSIT: { label: 'In transit', className: 'status-in-transit' },
  ARRIVED: { label: 'Arrived', className: 'status-in-transit' },
  DELIVERED: { label: 'Delivered', className: 'status-delivered' },
  CANCELLED: { label: 'Cancelled', className: 'status-cancelled' },
};

/**
 * Payment state is a separate axis from the delivery state, and it is what
 * revenue is derived from. Surfacing it removes the ambiguity that used to
 * exist where a delivered order did not say whether it had actually been paid.
 */
const PAYMENT = {
  PENDING_PAYMENT: { label: 'Payment pending', className: 'text-warn-400' },
  PAID: { label: 'Paid', className: 'text-ok-400' },
  REFUNDED: { label: 'Refunded', className: 'text-info-400' },
  VOIDED: { label: 'Voided', className: 'text-content-faint' },
};

export function StatusPill({ status }) {
  const state = STATES[status] || { label: status, className: 'status-pending' };
  return (
    <span className={`${state.className} whitespace-nowrap`}>
      {status === 'IN_TRANSIT' && <span className="status-dot animate-pulse" />}
  {status === 'ARRIVED' && <span className="status-dot animate-pulse" />}
      {status === 'DELIVERED' && (
        <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
        </svg>
      )}
      {state.label}
    </span>
  );
}

export function PaymentTag({ paymentStatus }) {
  if (!paymentStatus) return null;
  const state = PAYMENT[paymentStatus];
  if (!state) return null;
  return <span className={`text-xs font-medium ${state.className}`}>{state.label}</span>;
}

export const DELIVERY_STATES = STATES;
export { PAYMENT };
