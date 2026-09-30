# syntax=docker/dockerfile:1
#
# Frontend image: Next.js standalone output on a bare Node runtime.
#
# `output: 'standalone'` in next.config.mjs is what makes the runtime stage
# possible. It emits .next/standalone with only the modules actually imported by
# the build, plus a server.js that runs the app directly. Without it the runtime
# stage would need the full node_modules tree and the image would be several
# hundred megabytes of dependencies the running app never loads.

# ------------------------------------------------------------ deps resolution
FROM node:20-alpine AS deps

WORKDIR /app

# npm ci, not npm install: it installs exactly what the lockfile pins and fails
# if the lockfile and package.json disagree. That strictness is the point --
# this stage has to be reproducible, not merely successful.
#
# The lockfile is committed for this reason. It is ignored by convention for
# libraries, but this is an application: a clone without it cannot build at all.
COPY package.json package-lock.json ./
RUN npm ci

# ----------------------------------------------------------------- build stage
FROM node:20-alpine AS build

WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# These are build-time, not runtime: Next inlines rewrites into the server
# bundle, so the upstream addresses have to be known here. They are ARG not ENV
# so they do not persist into the runtime stage's environment.
#
# Defaults point at the compose service names. Because next.config.mjs proxies
# both upstreams through this origin rather than having the browser call them
# directly, no CORS configuration is needed and OSRM stays off the public
# internet.
ARG BACKEND_URL=http://backend:8080
ARG OSRM_URL=http://osrm:5000
ENV BACKEND_URL=$BACKEND_URL \
    OSRM_URL=$OSRM_URL \
    NEXT_TELEMETRY_DISABLED=1

# Build-time memory: the default Node heap is sized from the host's RAM, and a
# small CI runner makes the heap small enough that Next's bundler runs out mid
# route. 2 GB is not a guess made locally on a 32 GB laptop; it is the documented
# minimum for `next build`.
ENV NODE_OPTIONS="--max-old-space-size=2048"

RUN npm run build

# --------------------------------------------------------------- runtime stage
FROM node:20-alpine AS runtime

WORKDIR /app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 nextjs

# public/ is copied separately: Next's standalone output intentionally leaves
# static assets out, so forgetting this step yields a page that renders with no
# CSS and no JS rather than an obvious build error.
COPY --from=build --chown=nextjs:nodejs /app/public ./public

# The standalone bundle plus the node_modules subset it actually needs.
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
# Built separately because the standalone trace does not include .next/static.
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs:nodejs

EXPOSE 3000

# /login is server-rendered, so a 200 here means the route resolved and the
# server is up -- no dependency on a backend being healthy first, which would
# make the frontend unhealthy during a database restart.
#
# 127.0.0.1, not localhost, and that is not a style preference. On this Alpine
# image /etc/hosts maps `localhost` to ::1 as well as 127.0.0.1, next-server
# binds IPv4 only, and BusyBox wget treats the refused ::1 attempt as fatal
# instead of trying the next address. The symptom is a container that is
# serving 200s to the host while reporting itself unhealthy, and the health of
# the frontend is what gates anything depending on it.
#
# The port is read from the environment for the same reason as on the backend:
# a PaaS assigns the listening port, and a probe pinned to 3000 would report a
# perfectly serving application as unhealthy. 127.0.0.1 stays literal because
# resolving a name is exactly what broke the previous version of this check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO /dev/null http://127.0.0.1:${PORT:-3000}/login || exit 1

CMD ["node", "server.js"]