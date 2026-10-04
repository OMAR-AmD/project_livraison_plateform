'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import {
  adminGetStats,
  adminGetAllUsers,
  adminGetActivities,
  adminGetDeliveries,
} from '@/lib/api';
import Modal from '@/components/Modal';
import EmptyState from '@/components/EmptyState';
import StarRating from '@/components/StarRating';
import { useToast } from '@/components/Toast';

const ROLE_BADGE = {
  CLIENT: 'badge-client',
  LIVREUR: 'badge-livreur',
  ADMIN: 'badge-admin',
};

const STATE_ORDER = ['PENDING', 'ASSIGNED', 'IN_TRANSIT', 'ARRIVED', 'DELIVERED', 'CANCELLED'];
const STATE_META = {
  PENDING: { label: 'Pending', className: 'status-pending' },
  ASSIGNED: { label: 'Assigned', className: 'status-assigned' },
  IN_TRANSIT: { label: 'In transit', className: 'status-in-transit' },
  ARRIVED: { label: 'Arrived', className: 'status-in-transit' },
  DELIVERED: { label: 'Delivered', className: 'status-delivered' },
  CANCELLED: { label: 'Cancelled', className: 'status-cancelled' },
};
/* Widths of the pipeline bar, as a share of the total. */
const STATE_FILL = {
  PENDING: 'bg-ink-600',
  ASSIGNED: 'bg-info-500',
  IN_TRANSIT: 'bg-signal-500',
  ARRIVED: 'bg-signal-500',
  DELIVERED: 'bg-ok-500',
  CANCELLED: 'bg-danger-500',
};

const ACTIVITY_ICON = {
  USER_REGISTERED: { d: 'M15 19.128a9.38 9.38 0 002.625.372 9.337 9.337 0 004.121-.952 4.125 4.125 0 00-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 018.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0111.964-3.07M12 6.375a3.375 3.375 0 11-6.75 0 3.375 3.375 0 016.75 0zm8.25 2.25a2.625 2.625 0 11-5.25 0 2.625 2.625 0 015.25 0z', tone: 'text-info-400' },
  DELIVERY_DELIVERED: { d: 'M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z', tone: 'text-ok-400' },
  DELIVERY_RATED: { d: 'M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.563.563 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z', tone: 'text-warn-400' },
  DELIVERY_ASSIGNED: { d: 'M8.25 18.75a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m3 0h6m-9 0H3.375a1.125 1.125 0 01-1.125-1.125V14.25', tone: 'text-warn-400' },
  DELIVERY_IN_TRANSIT: { d: 'M8.25 18.75a1.5 1.5 0 01-3 0m3 0h6m-9 0H3.375a1.125 1.125 0 01-1.125-1.125V14.25m17.25 4.5a1.5 1.5 0 01-3 0m3 0H21a.75.75 0 00.75-.75v-3.75a3 3 0 00-3-3h-1.5V5.625', tone: 'text-signal-400' },
  DELIVERY_ARRIVED: { d: 'M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z', tone: 'text-signal-400' },
  DELIVERY_CANCELLED: { d: 'M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4', tone: 'text-danger-400' },
  DELIVERY_CREATED: { d: 'M12 4.5v15m7.5-7.5h-15', tone: 'text-info-400' },
};

function timeAgo(value) {
  if (!value) return '';
  const diff = Date.now() - new Date(value);
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} hour${hours > 1 ? 's' : ''} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days > 1 ? 's' : ''} ago`;
}

export default function DashboardPage() {
  const { user } = useAuth();
  const router = useRouter();
  const { addToast } = useToast();

  const [stats, setStats] = useState(null);
  const [activities, setActivities] = useState([]);
  const [counts, setCounts] = useState({});
  const [loading, setLoading] = useState(true);
  const [usersOpen, setUsersOpen] = useState(false);
  const [users, setUsers] = useState([]);
  const [usersLoading, setUsersLoading] = useState(false);

  useEffect(() => {
    if (!user) return;
    if (user.role !== 'ADMIN') {
      router.push('/dashboard/deliveries');
      return;
    }

    Promise.all([adminGetStats(), adminGetActivities(), adminGetDeliveries()])
      .then(([statsData, activityData, deliveries]) => {
        setStats(statsData);
        setActivities(activityData || []);
        const tally = {};
        STATE_ORDER.forEach((s) => (tally[s] = 0));
        (deliveries || []).forEach((d) => {
          if (tally[d.status] !== undefined) tally[d.status] += 1;
        });
        setCounts(tally);
      })
      .catch((err) => {
        console.error(err);
        addToast('Failed to load dashboard data', 'error');
      })
      .finally(() => setLoading(false));
  }, [user, router, addToast]);

  const openUsers = async () => {
    setUsersOpen(true);
    setUsersLoading(true);
    try {
      setUsers(await adminGetAllUsers());
    } catch (err) {
      addToast(err.message || 'Failed to load users', 'error');
    } finally {
      setUsersLoading(false);
    }
  };

  if (!user || user.role !== 'ADMIN' || loading) {
    return <div className="p-8 text-content-muted">Loading…</div>;
  }

  // The fetch may have failed (backend or database unreachable) while still
  // clearing `loading`. Rendering the metrics with a null `stats` crashed the
  // whole page with "Cannot read properties of null". Show a retryable error
  // instead of a red runtime box.
  if (!stats) {
    return (
      <div className="p-8">
        <EmptyState
          title="Dashboard unavailable"
          body="The statistics could not be loaded. The backend or database may be unreachable."
          action={
            <button onClick={() => window.location.reload()} className="btn-primary btn-sm">
              Retry
            </button>
          }
        />
      </div>
    );
  }

  const pipelineTotal = STATE_ORDER.reduce((sum, s) => sum + counts[s], 0);

  const metrics = [
    { label: 'Total users', value: stats.totalUsers },
    { label: 'Active deliveries', value: stats.activeDeliveries },
    { label: 'All deliveries', value: stats.totalDeliveries },
    { label: 'Completed today', value: stats.completedToday },
  ];

  return (
    <div className="space-y-8">
      {/* ── Header ────────────────────────────────────────────────────── */}
      <header className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="page-title">Operations</h1>
          <p className="mt-1 flex items-center gap-2 text-sm text-content-muted">
            {user.email}
            <span className="badge-admin">Admin</span>
          </p>
        </div>
        <p className="text-sm text-content-faint">
          {new Date().toLocaleDateString('en-GB', {
            weekday: 'long',
            day: 'numeric',
            month: 'long',
            year: 'numeric',
          })}
        </p>
      </header>

      {/* ── Metric strip ──────────────────────────────────────────────── */}
      {/* One surface with dividers reads as a single instrument panel rather
          than five competing cards. */}
      <section className="surface grid grid-cols-2 divide-line lg:grid-cols-4 lg:divide-x">
        {metrics.map((m) => (
          <div key={m.label} className="border-b border-line p-5 lg:border-b-0">
            <p className="stat-label">{m.label}</p>
            <p className="stat-value mt-1.5">{m.value}</p>
          </div>
        ))}
      </section>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* ── Revenue ────────────────────────────────────────────────── */}
        <section className="surface p-6 lg:col-span-2">
          <div className="flex items-start justify-between">
            <div>
              <p className="stat-label">Recognised revenue</p>
              <p className="mt-2 text-4xl font-bold tracking-tight text-content tabular">
                {(stats.totalRevenue || 0).toFixed(2)}{' '}
                <span className="text-lg font-semibold text-content-faint">MAD</span>
              </p>
              <p className="stat-meta">
                Captured payments only. Orders awaiting payment, voided orders and refunds are
                excluded.
              </p>
            </div>
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg border border-ok-500/25 bg-ok-500/10">
              <svg className="h-5 w-5 text-ok-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v12m-3-2.818l.879.659c1.171.879 3.07.879 4.242 0 1.172-.879 1.172-2.303 0-3.182C13.536 12.219 12.768 12 12 12c-.725 0-1.45-.22-2.003-.659-1.106-.879-1.106-2.303 0-3.182s2.9-.879 4.006 0l.415.33M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
          </div>

          {/* ── Pipeline ─────────────────────────────────────────────── */}
          <div className="mt-8 border-t border-line pt-6">
            <div className="flex items-baseline justify-between">
              <p className="stat-label">Delivery pipeline</p>
              <p className="text-sm text-content-muted tabular">{pipelineTotal} total</p>
            </div>

            {pipelineTotal > 0 ? (
              <>
                <div className="mt-3 flex h-2 w-full overflow-hidden rounded-full bg-line">
                  {STATE_ORDER.map((state) =>
                    counts[state] > 0 ? (
                      <div
                        key={state}
                        className={STATE_FILL[state]}
                        style={{ width: `${(counts[state] / pipelineTotal) * 100}%` }}
                        title={`${STATE_META[state].label}: ${counts[state]}`}
                      />
                    ) : null
                  )}
                </div>
                <ul className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-3">
                  {STATE_ORDER.map((state) => (
                    <li key={state} className="flex items-center justify-between gap-3 text-sm">
                      <span className="flex items-center gap-2 text-content-muted">
                        <span className={`h-2 w-2 rounded-full ${STATE_FILL[state]}`} />
                        {STATE_META[state].label}
                      </span>
                      <span className="font-semibold text-content tabular">{counts[state]}</span>
                    </li>
                  ))}
                </ul>
              </>
            ) : (
              <p className="mt-3 text-sm text-content-faint">No deliveries recorded yet.</p>
            )}
          </div>
        </section>

        {/* ── Rating + actions ───────────────────────────────────────── */}
        <div className="space-y-6">
          <section className="surface p-6">
            <p className="stat-label">Average rating</p>
            <p className="mt-2 flex items-baseline gap-1.5">
              <span className="text-4xl font-bold tracking-tight text-content tabular">
                {(stats.averageRating || 0).toFixed(1)}
              </span>
              <span className="text-content-faint">/ 5</span>
            </p>
            <div className="mt-3 flex gap-0.5" aria-hidden="true">
              {[1, 2, 3, 4, 5].map((n) => (
                <svg
                  key={n}
                  className={`h-4 w-4 ${
                    n <= Math.round(stats.averageRating || 0) ? 'text-warn-400' : 'text-line-strong'
                  }`}
                  viewBox="0 0 20 20"
                  fill="currentColor"
                >
                  <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                </svg>
              ))}
            </div>
          </section>

          <button onClick={openUsers} className="surface-interactive w-full p-6 text-left">
            <div className="flex h-11 w-11 items-center justify-center rounded-lg border border-signal-500/25 bg-signal-500/10">
              <svg className="h-5 w-5 text-signal-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 19.128a9.38 9.38 0 002.625.372 9.337 9.337 0 004.121-.952 4.125 4.125 0 00-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 018.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0111.964-3.07M12 6.375a3.375 3.375 0 11-6.75 0 3.375 3.375 0 016.75 0zm8.25 2.25a2.625 2.625 0 11-5.25 0 2.625 2.625 0 015.25 0z" />
              </svg>
            </div>
            <h2 className="mt-4 text-sm font-bold text-content">Manage users</h2>
            <p className="mt-1 text-sm text-content-muted">
              Review accounts, roles and verification state.
            </p>
          </button>
        </div>
      </div>

      {/* ── Activity ──────────────────────────────────────────────────── */}
      <section>
        <h2 className="section-title">Recent activity</h2>
        <div className="surface mt-3 divide-y divide-line overflow-hidden">
          {activities.length === 0 ? (
            <p className="p-6 text-sm text-content-faint">Nothing has happened yet.</p>
          ) : (
            activities.map((a, i) => {
              const meta = ACTIVITY_ICON[a.type] || {
                d: 'M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
                tone: 'text-content-faint',
              };
              return (
                <div key={i} className="flex items-center gap-4 p-4">
                  <span className={`shrink-0 ${meta.tone}`}>
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d={meta.d} />
                    </svg>
                  </span>
                  <p className="min-w-0 flex-1 truncate text-sm text-content-soft">{a.text}</p>
                  <span className="shrink-0 text-xs text-content-faint">{timeAgo(a.timestamp)}</span>
                </div>
              );
            })
          )}
        </div>
      </section>

      {/* ── Users modal ───────────────────────────────────────────────── */}
      <Modal isOpen={usersOpen} onClose={() => setUsersOpen(false)} title="Users">
        <div className="max-h-[60vh] overflow-x-auto">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr>
                <th className="table-head">Email</th>
                <th className="table-head">Role</th>
                <th className="table-head text-right">Deliveries</th>
                <th className="table-head">Rating</th>
                <th className="table-head">Verified</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {usersLoading ? (
                <tr>
                  <td colSpan={5} className="p-6 text-center text-content-faint">Loading…</td>
                </tr>
              ) : users.length === 0 ? (
                <tr>
                  <td colSpan={5} className="p-6 text-center text-content-faint">No users found.</td>
                </tr>
              ) : (
                users.map((u) => (
                  <tr key={u.id}>
                    <td className="table-cell">{u.email}</td>
                    <td className="table-cell">
                      <span className={ROLE_BADGE[u.role] || 'badge-client'}>
                        {u.role === 'LIVREUR' ? 'Courier' : u.role === 'ADMIN' ? 'Admin' : 'Client'}
                      </span>
                    </td>
                    {/* Only couriers have a track record. Clients and admins are
                        not assigned deliveries, so a dash is truthful where a 0
                        would imply a courier who has underperformed. */}
                    <td className="table-cell text-right text-content-muted tabular">
                      {u.role === 'LIVREUR' ? (
                        <span title={`${u.deliveredDeliveries ?? 0} delivered of ${
                          u.totalDeliveries ?? 0
                        } assigned`}>
                          {u.deliveredDeliveries ?? 0}
                          <span className="text-content-faint">
                            {' '}/ {u.totalDeliveries ?? 0}
                          </span>
                        </span>
                      ) : (
                        <span className="text-content-faint">—</span>
                      )}
                    </td>
                    <td className="table-cell">
                      {u.role === 'LIVREUR' ? (
                        <StarRating
                          value={u.averageRating}
                          count={u.ratingCount ?? 0}
                          size="sm"
                        />
                      ) : (
                        <span className="text-content-faint">—</span>
                      )}
                    </td>
                    <td className="table-cell text-content-muted">{u.verified ? 'Yes' : 'No'}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          <p className="mt-3 text-xs text-content-faint">
            Deliveries are shown as delivered / assigned. The rating is the mean of the
            ratings clients left on that courier&apos;s delivered orders.
          </p>
        </div>
        <div className="mt-6 flex justify-end">
          <button type="button" onClick={() => setUsersOpen(false)} className="btn-secondary">
            Close
          </button>
        </div>
      </Modal>
    </div>
  );
}
