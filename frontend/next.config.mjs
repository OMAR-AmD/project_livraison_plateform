/** @type {import('next').NextConfig} */

// Both upstreams are reached through the Next.js origin rather than called
// directly from the browser. Hard-coding localhost:5000 / :8080 in client code
// only works on one machine, and for the routing engine it would mean exposing
// OSRM publicly with permissive CORS.
const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';
const OSRM = process.env.OSRM_URL || 'http://localhost:5000';

const nextConfig = {
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
