'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import { getUnreadNotificationCount } from '@/lib/api';

/* 24×24 stroke icons, kept inline so the bundle carries no icon library. */
function makeIcon(displayName, d) {
  // className is merged rather than overridden: a caller passing
  // className="shrink-0" must not silently drop the icon's own size classes,
  // which would leave the SVG with no dimensions and let it fill its container.
  const Icon = ({ className = '', ...rest }) => (
    <svg
      className={`h-5 w-5 shrink-0 ${className}`}
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={1.5}
      aria-hidden="true"
      {...rest}
    >
      <path strokeLinecap="round" strokeLinejoin="round" d={d} />
    </svg>
  );
  Icon.displayName = displayName;
  return Icon;
}

const HomeIcon = makeIcon(
  'HomeIcon',
  'M2.25 12l8.954-8.955a1.126 1.126 0 011.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25'
);
const BoxIcon = makeIcon(
  'BoxIcon',
  'M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4'
);
const TruckIcon = makeIcon(
  'TruckIcon',
  'M8.25 18.75a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m3 0h6m-9 0H3.375a1.125 1.125 0 01-1.125-1.125V14.25m17.25 4.5a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m3 0H21a.75.75 0 00.75-.75v-3.75a3 3 0 00-3-3h-1.5V5.625a1.875 1.875 0 00-1.875-1.875H5.625A1.875 1.875 0 003.75 5.625v8.625'
);
const BellIcon = makeIcon(
  'BellIcon',
  'M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0'
);
const UserIcon = makeIcon(
  'UserIcon',
  'M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z'
);

const NAV = {
  CLIENT: [
    { label: 'My deliveries', href: '/dashboard/deliveries', Icon: BoxIcon },
    { label: 'Notifications', href: '/dashboard/notifications', Icon: BellIcon, badge: true },
    { label: 'Profile', href: '/dashboard/profile', Icon: UserIcon },
  ],
  LIVREUR: [
    { label: 'Assigned round', href: '/dashboard/deliveries', Icon: TruckIcon },
    { label: 'Notifications', href: '/dashboard/notifications', Icon: BellIcon, badge: true },
    { label: 'Profile', href: '/dashboard/profile', Icon: UserIcon },
  ],
  ADMIN: [
    { label: 'Dashboard', href: '/dashboard', Icon: HomeIcon },
    { label: 'All deliveries', href: '/dashboard/deliveries', Icon: BoxIcon },
    { label: 'Profile', href: '/dashboard/profile', Icon: UserIcon },
  ],
};

const ROLE_BADGE = {
  CLIENT: 'badge-client',
  LIVREUR: 'badge-livreur',
  ADMIN: 'badge-admin',
};

const ROLE_LABEL = {
  CLIENT: 'Client',
  LIVREUR: 'Courier',
  ADMIN: 'Admin',
};

function Logo() {
  return (
    <Link href="/" className="flex items-center gap-2.5">
      <span className="flex h-8 w-8 items-center justify-center rounded-md bg-signal-500 text-white">
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
          <path strokeLinecap="square" strokeLinejoin="miter" d="M2 12h5l3-8 4 16 3-8h5" />
        </svg>
      </span>
      <span className="text-base font-bold tracking-tight text-content">SwiftDeliver</span>
    </Link>
  );
}

/**
 * `open` / `onOpenChange` make the drawer a controlled component so the layout
 * can own the mobile trigger. On desktop the trigger is not rendered at all and
 * the rail is always visible.
 */
export default function Sidebar({ open = false, onOpenChange = () => {} }) {
  const { user, logout } = useAuth();
  const pathname = usePathname();
  const [unread, setUnread] = useState(0);

  const close = () => onOpenChange(false);

  // Unread count is kept live over SSE; EventSource cannot send an
  // Authorization header, which is why the token travels as a query parameter.
  useEffect(() => {
    if (user?.role !== 'CLIENT' && user?.role !== 'LIVREUR') return;

    let source;
    let cancelled = false;

    const fetchCount = async () => {
      try {
        const res = await getUnreadNotificationCount();
        if (!cancelled) setUnread(res.count);
      } catch (e) {
        console.error('Failed to fetch unread count', e);
      }
    };

    fetchCount();

    import('@/lib/api').then(({ getNotificationStreamUrl }) => {
      if (cancelled) return;
      source = new EventSource(getNotificationStreamUrl());
      source.addEventListener('notification', () => {
        fetchCount();
        // Let other mounted views know their data is stale.
        window.dispatchEvent(new Event('backendUpdated'));
      });
    });

    const onManualUpdate = () => fetchCount();
    window.addEventListener('notificationsUpdated', onManualUpdate);

    return () => {
      cancelled = true;
      source?.close();
      window.removeEventListener('notificationsUpdated', onManualUpdate);
    };
  }, [user]);

  const items = NAV[user?.role] || NAV.CLIENT;

  const isActive = (href) =>
    href === '/dashboard' ? pathname === '/dashboard' : pathname.startsWith(href);

  const panel = (
    <div className="flex h-full flex-col bg-surface-sunken">
      <div className="border-b border-line px-5 py-5">
        <Logo />
      </div>

      <nav className="flex-1 overflow-y-auto p-3">
        <ul className="space-y-1">
          {items.map(({ label, href, Icon, badge }) => {
            const active = isActive(href);
            return (
              <li key={href}>
                <Link
                  href={href}
                  onClick={close}
                  aria-current={active ? 'page' : undefined}
                  className={`flex items-center justify-between rounded-md border-l-2 py-2.5 pl-4 pr-3 text-sm transition-colors ${
                    active
                      ? 'border-signal-500 bg-surface text-content'
                      : 'border-transparent text-content-muted hover:bg-surface hover:text-content'
                  }`}
                >
                  <span className="flex min-w-0 items-center gap-3">
                    <Icon className={active ? 'text-signal-500' : ''} />
                    <span className="truncate font-medium">{label}</span>
                  </span>
                  {badge && unread > 0 && (
                    <span className="rounded bg-danger-500 px-1.5 py-0.5 text-[10px] font-bold text-white">
                      {unread}
                    </span>
                  )}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>

      <div className="border-t border-line p-3">
        <div className="flex items-center gap-3 rounded-md p-2">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-md border border-line bg-surface text-sm font-bold text-content">
            {user?.avatarUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={user.avatarUrl}
                alt=""
                className="h-full w-full object-cover"
                onError={(e) => {
                  e.target.style.display = 'none';
                }}
              />
            ) : (
              (user?.firstName || user?.email || 'U').charAt(0).toUpperCase()
            )}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-content">
              {user?.firstName
                ? `${user.firstName} ${user.lastName || ''}`.trim()
                : user?.email || 'User'}
            </p>
            <span className={ROLE_BADGE[user?.role] || 'badge-client'}>
              {ROLE_LABEL[user?.role] || 'Client'}
            </span>
          </div>
        </div>

        <button onClick={logout} className="btn-secondary btn-sm mt-2 w-full">
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0013.5 3h-6a2.25 2.25 0 00-2.25 2.25v13.5A2.25 2.25 0 007.5 21h6a2.25 2.25 0 002.25-2.25V15m3 0l3-3m0 0l-3-3m3 3H9" />
          </svg>
          Sign out
        </button>
      </div>
    </div>
  );

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 z-40 animate-fade-in bg-black/60 backdrop-blur-sm md:hidden"
          onClick={close}
          aria-hidden="true"
        />
      )}

      {/* Mobile drawer */}
      <aside
        className={`fixed left-0 top-0 z-50 h-screen w-64 border-r border-line transition-transform duration-200 ease-out md:hidden ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
        aria-hidden={!open}
      >
        <div className="relative h-full">
          {panel}
          <button
            onClick={close}
            aria-label="Close navigation"
            className="absolute right-3 top-5 rounded-md p-1.5 text-content-faint transition-colors hover:bg-surface-hover hover:text-content"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </aside>

      {/* Desktop rail */}
      <aside className="fixed left-0 top-0 z-30 hidden h-screen w-64 border-r border-line md:block">
        {panel}
      </aside>
    </>
  );
}
