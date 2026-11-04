/** @type {import('next').NextConfig} */

// Both upstreams are reached through the Next.js origin rather than called
// directly from the browser. Hard-coding localhost:5000 / :8080 in client code
// only works on one machine, and for the routing engine it would mean exposing
// OSRM publicly with permissive CORS.
const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';
const OSRM = process.env.OSRM_URL || 'http://localhost:5000';

// Security headers applied to every page. The CSP still allows inline/eval
// because Next 14's runtime uses inline bootstrap scripts; tightening it to a
// nonce-based policy is a follow-up, not a reason to ship no CSP at all.
const securityHeaders = [
  {
    key: 'Content-Security-Policy',
    value: [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https:",
      "font-src 'self' data:",
      "connect-src 'self' https: wss:",
      "media-src 'self'",
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'self'",
      "frame-ancestors 'none'",
    ].join('; '),
  },
  { key: 'Strict-Transport-Security', value: 'max-age=31536000; includeSubDomains' },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'no-referrer' },
  { key: 'Permissions-Policy', value: 'geolocation=(self), camera=(self), microphone=()' },
];

const nextConfig = {
  // Emits .next/standalone with only the modules actually imported, plus a
  // ready-to-run server.js. This is what lets the runtime image skip `npm ci`
  // entirely and stay around 150 MB instead of carrying node_modules and the
  // whole build toolchain. It only affects `next build`; `next dev` is
  // unchanged, so local development is unaffected.
  output: 'standalone',

  async headers() {
    return [
      {
        source: '/(.*)',
        headers: securityHeaders,
      },
    ];
  },

  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${BACKEND}/api/:path*`,
      },
      {
        // Polyline geometry for the maps. The path is passed through untouched
        // so the semicolon-separated coordinate list in /route/v1/driving
        // survives the proxy.
        source: '/osrm/:path*',
        destination: `${OSRM}/:path*`,
      },
    ];
  },
};

export default nextConfig;
