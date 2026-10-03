'use client';

/**
 * The single basemap tile layer used by every map in the application.
 *
 * Why this exists instead of a bare <TileLayer> in each component:
 *
 * 1. One place to change the tile source. NEXT_PUBLIC_TILE_URL is read from the
 *    environment, so pointing the platform at a self-hosted tile server is an
 *    environment change rather than a code change across four components.
 *
 * 2. Failures stop being silent. The previous configuration had no tileerror
 *    handler, so when the tile server throttled or was unreachable the map
 *    rendered as an empty grey rectangle with markers floating on it and no
 *    indication that anything had gone wrong. That is the "sometimes the map
 *    doesn't load" symptom: markers and routes are SVG and always draw, the
 *    basemap is a set of images and quietly does not.
 *
 * 3. Attribution stays correct. OpenStreetMap's tile usage policy requires
 *    visible attribution, and the dark theme inverts the tile pane with CSS,
 *    which would render light attribution text unreadable if it were inverted
 *    along with the tiles. The failure notice is therefore drawn outside the
 *    tile pane.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { TileLayer } from 'react-leaflet';

/**
 * Public OpenStreetMap tiles by default.
 *
 * This is a free service whose usage policy discourages exactly this kind of
 * application use, so it throttles under load and can return nothing at all.
 * Self-hosting is the durable answer; until then the failure is retried and
 * reported rather than silent.
 */
const DEFAULT_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

const TILE_URL =
  (typeof process !== 'undefined' && process.env.NEXT_PUBLIC_TILE_URL) || DEFAULT_TILE_URL;

const ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

/**
 * Shown in place of a tile that could not be fetched, so a broken basemap looks
 * broken instead of empty. Its path is also how a real tile load is told apart
 * from a substituted fallback, so it is defined once here.
 */
const FALLBACK_TILE = '/map-tile-fallback.svg';

/** Consecutive failures before the basemap is declared unavailable. */
const FAILURE_THRESHOLD = 4;

export default function BasemapLayer({ onStatusChange }) {
  const [failures, setFailures] = useState(0);
  const [degraded, setDegraded] = useState(false);
  const [retry, setRetry] = useState(0);
  const retryTimer = useRef(null);

  /**
   * Counts errors so a single dropped tile does not raise the banner, while a
   * genuinely unreachable tile server does. A ref, because it is read from
   * Leaflet's event callbacks rather than from render.
   */
  const errored = useRef(0);

  useEffect(() => {
    onStatusChange?.({ degraded, failures, retrying: retry > 0 });
  }, [degraded, failures, retry, onStatusChange]);

  useEffect(() => () => clearTimeout(retryTimer.current), []);

  const handleError = useCallback(() => {
    errored.current += 1;
    setFailures(errored.current);
    if (errored.current >= FAILURE_THRESHOLD) {
      setDegraded(true);
      // Back off before retrying so a throttled server is not hammered.
      clearTimeout(retryTimer.current);
      retryTimer.current = setTimeout(() => setRetry((n) => n + 1), 1500 * errored.current);
    }
  }, []);

  /**
   * Recovery is detected from `tileload`, not from the layer's `load` event.
   *
   * When errorTileUrl is set, a failed tile is swapped for the fallback image
   * and the layer still reports that it finished loading, so `load` fires just
   * as happily for a completely broken basemap, and it fires more than once per
   * view. Reading the tile's own src tells the two cases apart exactly, with no
   * timing guesswork: a tile actually served by the tile server means the
   * basemap is back.
   */
  const handleTileLoad = useCallback((event) => {
    const src = event?.tile?.src || '';
    if (src.includes(FALLBACK_TILE)) {
      return; // That "successful" load was the fallback standing in for a failure.
    }
    errored.current = 0;
    setFailures(0);
    setDegraded(false);
  }, []);

  // Changing the url makes Leaflet discard its cached tiles and refetch, which
  // is the supported way to force a retry. Leaflet exposes no retry API.
  const tileUrl =
    retry === 0 ? TILE_URL : `${TILE_URL}${TILE_URL.includes('?') ? '&' : '?'}retry=${retry}`;

  const manualRetry = useCallback(() => {
    clearTimeout(retryTimer.current);
    errored.current = 0;
    setFailures(0);
    setRetry((n) => n + 1);
  }, []);

  return (
    <>
      <TileLayer
        url={tileUrl}
        attribution={ATTRIBUTION}
        eventHandlers={{ tileerror: handleError, tileload: handleTileLoad }}
        // Zoom past the tile grid: Leaflet upscales the deepest native tiles
        // instead of refusing to zoom in any further.
        maxZoom={19}
        maxNativeZoom={18}
        // Keeps the map legible instead of blank while retrying.
        errorTileUrl={FALLBACK_TILE}
      />

      {degraded && <BasemapNotice onRetry={manualRetry} />}
    </>
  );
}

/**
 * Drawn as an overlay rather than a Leaflet control so it inherits the
 * dashboard's typography and is not inverted by the tile-pane CSS filter.
 */
function BasemapNotice({ onRetry }) {
  return (
    <div
      role="status"
      className="pointer-events-none absolute inset-x-0 top-3 z-[1000] mx-auto w-fit max-w-[92%]"
    >
      <div className="pointer-events-auto flex items-center gap-3 rounded-lg border border-warn-500/30 bg-surface-raised/95 px-3.5 py-2 text-xs text-content-soft shadow-raised backdrop-blur">
        <span className="inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-warn-400" />
        <span>
          Basemap unavailable. Positions and routes are still accurate, but the
          background map could not be loaded.
        </span>
        <button type="button" onClick={onRetry} className="btn-ghost btn-sm shrink-0">
          Retry
        </button>
      </div>
    </div>
  );
}
