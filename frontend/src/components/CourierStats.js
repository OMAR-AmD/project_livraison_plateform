'use client';

import StarRating from '@/components/StarRating';

/**
 * A courier's own track record: how much they have completed, what is still in
 * flight, and what clients thought of them.
 *
 * The counts come from the deliveries the courier was assigned, which is the
 * only place a track record exists in this schema — there is no courier profile
 * row to hold it.
 *
 * Shown as a single divided strip rather than separate cards, matching the
 * admin operations panel, so both roles read as the same instrument.
 *
 * While `stats` is null the numbers are skeleton bars, not zeros: showing 0
 * before the request returns would briefly claim a courier had done nothing.
 */
const TILES = [
  {
    key: 'completedDeliveries',
    label: 'Completed',
    hint: 'delivered orders',
    accent: 'text-ok-400',
  },
  {
    key: 'activeDeliveries',
    label: 'In progress',
    hint: 'assigned or in transit',
    accent: 'text-signal-400',
  },
  {
    key: 'cancelledDeliveries',
    label: 'Cancelled',
    hint: 'orders you were given',
    accent: 'text-warn-400',
  },
];

export default function CourierStats({ stats }) {
  return (
    <section className="surface p-5" aria-label="Your track record">
      <div className="grid grid-cols-2 gap-x-4 gap-y-5 sm:grid-cols-4 sm:divide-x sm:divide-line">
        {TILES.map((t, i) => (
          <div
            key={t.key}
            className={i === 0 ? 'sm:pl-0' : ''}
          >
            <p className="stat-label">{t.label}</p>
            {stats ? (
              <>
                <p className={`stat-value mt-1.5 ${t.accent}`}>{stats[t.key]}</p>
                <p className="stat-meta">{t.hint}</p>
              </>
            ) : (
              <>
                <div className="mt-2 h-7 w-12 animate-pulse rounded bg-surface-raised" />
                <div className="mt-2 h-3 w-20 animate-pulse rounded bg-surface-raised" />
              </>
            )}
          </div>
        ))}

        <div className="col-span-2 sm:col-span-1">
          <p className="stat-label">Average rating</p>
          <div className="mt-2.5">
            {stats ? (
              <>
                <StarRating value={stats.averageRating} count={stats.ratingCount} size="lg" />
                <p className="stat-meta">
                  {stats.ratingCount > 0
                    ? 'from your rated deliveries'
                    : 'no delivery rated yet'}
                </p>
              </>
            ) : (
              <div className="flex items-center gap-2">
                <div className="h-5 w-5 animate-pulse rounded bg-surface-raised" />
                <div className="h-5 w-10 animate-pulse rounded bg-surface-raised" />
              </div>
            )}
          </div>
        </div>
      </div>

      {stats && stats.totalDeliveries > stats.completedDeliveries + stats.cancelledDeliveries && (
        <p className="mt-4 border-t border-line pt-3 text-xs text-content-faint">
          {stats.totalDeliveries} order{stats.totalDeliveries === 1 ? '' : 's'} have been assigned to
          you in total. Cancelled orders are counted separately because they are not a
          performance figure.
        </p>
      )}
    </section>
  );
}
