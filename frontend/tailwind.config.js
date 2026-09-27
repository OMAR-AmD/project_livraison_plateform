/** @type {import('tailwindcss').Config} */

/**
 * SwiftDeliver design system.
 *
 * The palette is built from three ideas:
 *
 *  1. A single neutral ramp ("ink") carries the entire dark UI. Nothing in the
 *     interface is blue-grey by accident — every surface is a deliberate step on
 *     one ramp, which is what makes a dark theme read as designed rather than
 *     as a pile of Tailwind defaults.
 *  2. Exactly one accent ("signal") — a hi-visibility orange borrowed from road
 *     markings and courier vests. It is reserved for the primary action and for
 *     live/in-motion state, so colour always carries meaning.
 *  3. Everything else is semantic. Components reference roles (surface, line,
 *     content) rather than raw ramp steps, so the theme can be re-tuned in this
 *     one file without touching a single component.
 */
module.exports = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    fontFamily: {
      sans: ['var(--font-geist)', 'system-ui', '-apple-system', 'sans-serif'],
      mono: ['var(--font-geist-mono)', 'ui-monospace', 'monospace'],
    },
    extend: {
      colors: {
        /* Neutral ramp — the only place raw greys are defined. */
        ink: {
          50: '#F6F7F8',
          100: '#E9EBED',
          200: '#D2D7DC',
          300: '#AEB5BD',
          400: '#868D97',
          500: '#666D77',
          600: '#4B525B',
          700: '#383E46',
          800: '#262A31',
          900: '#171A1F',
          950: '#0C0E11',
        },

        /* Hi-vis accent. Reserved for primary actions and live motion. */
        signal: {
          300: '#FDBA74',
          400: '#FB923C',
          500: '#F97316',
          600: '#EA580C',
          700: '#C2410C',
        },

        /* Semantic states. */
        ok: { 300: '#6EE7B7', 400: '#34D399', 500: '#10B981', 600: '#059669' },
        info: { 300: '#7DD3FC', 400: '#38BDF8', 500: '#0EA5E9', 600: '#0284C7' },
        warn: { 300: '#FCD34D', 400: '#FBBF24', 500: '#F59E0B', 600: '#D97706' },
        danger: { 300: '#FCA5A5', 400: '#F87171', 500: '#EF4444', 600: '#DC2626' },
        violet: { 300: '#C4B5FD', 400: '#A78BFA', 500: '#8B5CF6', 600: '#7C3AED' },

        /* ── Semantic aliases used by components ────────────────────────
           Components should prefer these over the `ink` ramp so that the
           theme stays coherent if the ramp is retuned.                      */

        // Page background — the darkest step.
        canvas: '#0C0E11',

        // Raised surfaces: cards, panels, sheets.
        surface: {
          DEFAULT: '#121519',
          raised: '#171A1F',
          sunken: '#0F1114',
          hover: '#1E222A',
        },

        // Hairlines and borders.
        line: {
          DEFAULT: '#242830',
          strong: '#343A45',
          soft: '#1B1E24',
        },

        // Text.
        content: {
          DEFAULT: '#F2F4F6',
          soft: '#C3C9D1',
          muted: '#868D97',
          faint: '#5C636D',
        },
      },

      borderRadius: {
        sm: '6px',
        DEFAULT: '10px',
        md: '10px',
        lg: '14px',
        xl: '20px',
      },

      boxShadow: {
        // Dark UIs read better with a border plus a soft ambient shadow than
        // with a hard drop shadow, so these stay deliberately shallow.
        card: '0 1px 2px rgba(0,0,0,0.4), 0 0 0 1px rgba(255,255,255,0.02)',
        raised: '0 8px 24px -8px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.03)',
        overlay: '0 24px 64px -16px rgba(0,0,0,0.75)',
      },

      keyframes: {
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        'slide-up': {
          from: { opacity: '0', transform: 'translateY(12px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'slide-in-right': {
          from: { opacity: '0', transform: 'translateX(16px)' },
          to: { opacity: '1', transform: 'translateX(0)' },
        },
        'pulse-ring': {
          '0%': { transform: 'scale(0.9)', opacity: '0.7' },
          '70%': { transform: 'scale(1.6)', opacity: '0' },
          '100%': { transform: 'scale(1.6)', opacity: '0' },
        },
      },
      animation: {
        'fade-in': 'fade-in 200ms ease-out',
        'slide-up': 'slide-up 240ms cubic-bezier(0.16, 1, 0.3, 1)',
        'slide-in-right': 'slide-in-right 240ms cubic-bezier(0.16, 1, 0.3, 1)',
        'pulse-ring': 'pulse-ring 2s cubic-bezier(0.24, 0, 0.38, 1) infinite',
      },
    },
  },
  plugins: [],
};
