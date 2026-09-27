'use client';

/**
 * The forward-only control a courier uses to advance a delivery.
 *
 * This replaces a <select> that offered every status at once. Two problems with
 * that: it let a courier move a delivery backwards (IN_TRANSIT back to ASSIGNED),
 * and it made the normal case — "this is done" — indistinguishable from the
 * destructive one. The state machine only moves forward, so the UI now offers
 * exactly the one transition that is legal from the current state.
 */
const NEXT = {
  ASSIGNED: { status: 'IN_TRANSIT', label: 'Start delivery', className: 'btn-primary btn-sm' },
  IN_TRANSIT: { status: 'DELIVERED', label: 'Mark delivered', className: 'btn-primary btn-sm' },
};

export default function StopAction({ delivery, onChange, block = false }) {
  const next = NEXT[delivery.status];

  if (!next) {
    return (
      <span className="text-xs font-medium text-content-faint">
        {delivery.status === 'DELIVERED' ? 'Completed' : 'No action available'}
      </span>
    );
  }

  return (
    <button
      onClick={() => onChange(delivery.id, next.status)}
      className={`${next.className} ${block ? 'w-full' : ''}`}
    >
      {next.label}
    </button>
  );
}
