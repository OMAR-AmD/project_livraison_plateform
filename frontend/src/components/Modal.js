'use client';

import { useEffect, useRef } from 'react';

/**
 * Base modal shell.
 *
 * Previously the backdrop was a non-interactive div and Escape did nothing, so a
 * dialog could only be dismissed by clicking outside it or hitting a tiny
 * cross. It now behaves like a dialog: Escape closes, focus moves in on open
 * and returns to the trigger on close, the backdrop is blurred and clearly
 * separate from the panel, and the body behind it cannot scroll.
 */
export default function Modal({ isOpen, onClose, title, description, children, footer, size = 'md' }) {
  const panelRef = useRef(null);
  const previouslyFocused = useRef(null);
  const prevOverflow = useRef('');
  const prevPadding = useRef('');
  const wasOpen = useRef(false);
  // Parents pass inline `onClose` arrows, so its identity changes on every
  // render. Reading it through a ref keeps this effect tied to the open/close
  // transitions instead of re-running (and re-stealing focus) on each render.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!isOpen) {
      // True close transition only: restore scroll and return focus to the
      // element that opened the dialog. Parent re-renders while closed do nothing.
      if (wasOpen.current) {
        wasOpen.current = false;
        document.body.style.overflow = prevOverflow.current;
        document.body.style.paddingRight = prevPadding.current;
        if (previouslyFocused.current instanceof HTMLElement) {
          previouslyFocused.current.focus();
        }
      }
      return;
    }
    if (wasOpen.current) return; // Already open: a parent re-render must not touch focus.
    wasOpen.current = true;

    previouslyFocused.current = document.activeElement;

    const onKeyDown = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onCloseRef.current?.();
      }
    };

    document.addEventListener('keydown', onKeyDown);

    // Lock background scroll, compensating for the scrollbar so the layout
    // behind the overlay does not shift sideways as it disappears.
    const { body } = document;
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    prevOverflow.current = body.style.overflow;
    prevPadding.current = body.style.paddingRight;
    body.style.overflow = 'hidden';
    if (scrollbarWidth > 0) body.style.paddingRight = `${scrollbarWidth}px`;

    // Focus the panel once, on open, so screen readers announce the dialog.
    // Never again while open: re-focusing here is what dismissed the mobile
    // keyboard on every background refresh.
    panelRef.current?.focus();

    return () => {
      document.removeEventListener('keydown', onKeyDown);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  if (!isOpen) return null;

  const widths = {
    sm: 'max-w-sm',
    md: 'max-w-md',
    lg: 'max-w-2xl',
    xl: 'max-w-4xl',
  };

  return (
    // z-index must clear Leaflet, not just our own components. Leaflet ships its
    // own scale: .leaflet-control sits at 800 and .leaflet-top/.leaflet-bottom at
    // 1000, and the map's container is not a stacking context, so those compete
    // with us directly. At z-100 the dialog was painted UNDER the zoom buttons:
    // the map looked like it was in front of the modal and the +/- stayed
    // clickable straight through the backdrop. 1100 is above everything Leaflet
    // uses; Toast sits at 1200 so a message raised while a dialog is open is
    // still visible. Keep those two in step with verify_assign_ui.js, which
    // fails if this number drops back under 1000.
    <div className="fixed inset-0 z-[1100] flex items-end justify-center p-0 sm:items-center sm:p-4">
      <div
        className="absolute inset-0 animate-fade-in bg-black/70 backdrop-blur-sm"
        onClick={onClose}
        aria-hidden="true"
      />

      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={typeof title === 'string' ? title : undefined}
        tabIndex={-1}
        className={`relative flex h-[100dvh] max-h-[100dvh] w-full ${widths[size]} animate-slide-up flex-col overflow-hidden border border-line bg-surface shadow-overlay outline-none rounded-none sm:h-auto sm:max-h-[92vh] sm:rounded-xl`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div className="min-w-0">
            <h2 className="text-base font-bold text-content">{title}</h2>
            {description && (
              <p className="mt-0.5 text-sm text-content-muted">{description}</p>
            )}
          </div>
          <button
            onClick={onClose}
            aria-label="Close dialog"
            className="-mr-1 -mt-1 shrink-0 rounded-md p-1.5 text-content-faint transition-colors hover:bg-surface-hover hover:text-content"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-5">{children}</div>

        {footer && <div className="border-t border-line px-5 py-4">{footer}</div>}
      </div>
    </div>
  );
}
