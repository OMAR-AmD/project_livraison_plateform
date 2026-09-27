'use client';

/**
 * Toast Notification System
 *
 * Provides a <ToastProvider> that renders a stack of floating toasts at the
 * top-right corner.  Any component inside the provider can call:
 *
 *   const { addToast } = useToast();
 *   addToast('Saved successfully', 'success');
 *
 * Toasts auto-dismiss after 4 seconds with a smooth slide-out animation.
 */

import { createContext, useContext, useState, useCallback } from 'react';

const ToastContext = createContext(undefined);

/* ------------------------------------------------------------------ */
/*  Inline SVG icons                                                   */
/* ------------------------------------------------------------------ */

function CheckIcon() {
  return (
    <svg
      className="w-5 h-5 shrink-0 text-ok-400"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
      <polyline points="22 4 12 14.01 9 11.01" />
    </svg>
  );
}

function ErrorIcon() {
  return (
    <svg
      className="w-5 h-5 shrink-0 text-danger-400"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="12" r="10" />
      <line x1="15" y1="9" x2="9" y2="15" />
      <line x1="9" y1="9" x2="15" y2="15" />
    </svg>
  );
}

function InfoIcon() {
  return (
    <svg
      className="w-5 h-5 shrink-0 text-info-400"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="12" r="10" />
      <line x1="12" y1="16" x2="12" y2="12" />
      <line x1="12" y1="8" x2="12.01" y2="8" />
    </svg>
  );
}

function CloseIcon() {
  return (
    <svg
      className="w-4 h-4"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  );
}

/* ------------------------------------------------------------------ */
/*  Icon + border colour lookup                                         */
/* ------------------------------------------------------------------ */

const iconMap = {
  success: <CheckIcon />,
  error: <ErrorIcon />,
  info: <InfoIcon />,
};

const borderColorMap = {
  success: 'border-ok-500/30',
  error: 'border-danger-500/30',
  info: 'border-info-500/30',
};

/* ------------------------------------------------------------------ */
/*  Provider                                                           */
/* ------------------------------------------------------------------ */

let nextId = 0;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);

  /**
   * Remove a toast with a 300ms exit animation.
   */
  const removeToast = useCallback((id) => {
    // Phase 1 — mark as removing (triggers slide-out CSS)
    setToasts((prev) =>
      prev.map((t) => (t.id === id ? { ...t, removing: true } : t)),
    );

    // Phase 2 — actually remove from the DOM after the animation finishes
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 300);
  }, []);

  /**
   * Show a toast.
   *
   * @param {string} message
   * @param {'success'|'error'|'info'} type
   */
  const addToast = useCallback(
    (message, type = 'info') => {
      const id = ++nextId;

      setToasts((prev) => [
        ...prev,
        { id, message, type, removing: false },
      ]);

      // Auto-dismiss after 4 s (minus the 300ms exit animation)
      setTimeout(() => removeToast(id), 4000);
    },
    [removeToast],
  );

  return (
    <ToastContext.Provider value={{ addToast, removeToast }}>
      {children}

      {/* ---- toast container ----
           Bottom-centre rather than top-right: page headers place their primary
           action in the top-right corner, and the chat launcher already occupies
           the bottom-right. This position collides with neither. */}
      <div
        aria-live="polite"
        className="pointer-events-none fixed bottom-6 left-1/2 z-[60] flex w-full max-w-sm -translate-x-1/2 flex-col items-center gap-3 px-4"
      >
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`
              pointer-events-auto flex w-full items-start gap-3
              rounded-lg border ${borderColorMap[toast.type]}
              bg-surface-raised/95 px-4 py-3 shadow-overlay backdrop-blur-md
              transition-all duration-300 ease-out
              ${
                toast.removing
                  ? 'translate-y-2 opacity-0'
                  : 'translate-y-0 opacity-100 animate-toast-in'
              }
            `}
            role="alert"
          >
            {/* icon */}
            {iconMap[toast.type]}

            {/* message */}
            <p className="flex-1 text-sm text-content leading-snug pt-px">
              {toast.message}
            </p>

            {/* close button */}
            <button
              onClick={() => removeToast(toast.id)}
              className="shrink-0 text-content-faint hover:text-content-soft transition-colors cursor-pointer"
              aria-label="Dismiss notification"
            >
              <CloseIcon />
            </button>
          </div>
        ))}
      </div>

      {/* ---- keyframe (injected once) ---- */}
      <style jsx global>{`
        @keyframes toast-in {
          from {
            transform: translateY(12px);
            opacity: 0;
          }
          to {
            transform: translateY(0);
            opacity: 1;
          }
        }
        .animate-toast-in {
          animation: toast-in 0.28s cubic-bezier(0.16, 1, 0.3, 1) forwards;
        }
      `}</style>
    </ToastContext.Provider>
  );
}

/**
 * Hook — must be used inside <ToastProvider>.
 */
export function useToast() {
  const ctx = useContext(ToastContext);

  if (ctx === undefined) {
    throw new Error('useToast must be used within a <ToastProvider>');
  }

  return ctx;
}
