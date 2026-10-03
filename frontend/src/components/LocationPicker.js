import { useState, useEffect, useRef } from 'react';
import dynamic from 'next/dynamic';

const LocationPickerMap = dynamic(() => import('./LocationPickerMap'), { ssr: false });

// Nominatim answers with the full administrative hierarchy in every local
// script. For Casablanca that is French + Arabic + Tifinagh and it routinely
// runs past the 255-character column on the order, so the insert failed and the
// server masked it as "a record already exists" (a 409), which points nowhere
// useful. Keep the first few meaningful components and cap the length: the
// address is a human label for the order, not a geocoding record.
const MAX_ADDRESS_CHARS = 200;

function formatAddress(raw) {
  const parts = (raw || '').split(',').map((s) => s.trim()).filter(Boolean);
  const joined = parts.slice(0, 3).join(', ') || (raw || '').trim();
  return joined.length > MAX_ADDRESS_CHARS ? joined.slice(0, MAX_ADDRESS_CHARS) : joined;
}

/** Stable key for a coordinate pair, so the same position is geocoded once. */
function coordKey(lat, lng) {
  return `${lat},${lng}`;
}

/** Turns coordinates into the short human label used across the app. */
async function reverseGeocode(lat, lng) {
  const res = await fetch(
    `https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lng}`,
  );
  const data = await res.json();
  return data && data.display_name ? formatAddress(data.display_name) : null;
}

export default function LocationPicker({ label, placeholder, address, lat, lng, onLocationChange, referenceLat, referenceLng, referenceLabel }) {
  const [query, setQuery] = useState(address || '');
  const [suggestions, setSuggestions] = useState([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [isSearching, setIsSearching] = useState(false);
  const timeoutRef = useRef(null);
  // Coordinates already turned into an address, so a map click is not
  // reverse-geocoded a second time when its props flow back in.
  const lastGeocodedRef = useRef(null);

  // Sync external address changes
  useEffect(() => {
    if (address !== query && !showSuggestions) {
      setQuery(address || '');
    }
  }, [address]);

  // The "Use my GPS location" button sets the coordinates on the parent
  // directly, without going through the map handlers below, so the pin moved
  // but the text box stayed empty. Resolve a label for any coordinate that
  // arrives from the outside, not just for map clicks.
  useEffect(() => {
    if (lat == null || lng == null) return;
    const key = coordKey(lat, lng);
    if (lastGeocodedRef.current === key) return;
    lastGeocodedRef.current = key;

    (async () => {
      let label = null;
      try {
        label = await reverseGeocode(lat, lng);
      } catch (err) {
        console.error("Reverse geocoding error:", err);
      }
      // A newer position may have arrived while we were fetching: drop this one.
      if (lastGeocodedRef.current !== key) return;
      // Fall back to the raw coordinates so the field is never left empty.
      const resolved = label || `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
      setQuery(resolved);
      setShowSuggestions(false);
      onLocationChange({ address: resolved, lat, lng });
    })();
    // onLocationChange is a fresh closure each render; the key guard above is
    // what stops repeated geocoding, not the dependency list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lat, lng]);

  // Debounced Nominatim Search
  useEffect(() => {
    if (!query || query.length < 3 || !showSuggestions) {
      setSuggestions([]);
      return;
    }

    if (timeoutRef.current) clearTimeout(timeoutRef.current);

    timeoutRef.current = setTimeout(async () => {
      setIsSearching(true);
      try {
        const res = await fetch(`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(query)}&limit=5`);
        const data = await res.json();
        setSuggestions(data);
      } catch (err) {
        console.error("Geocoding error:", err);
      } finally {
        setIsSearching(false);
      }
    }, 500);

    return () => clearTimeout(timeoutRef.current);
  }, [query, showSuggestions]);

  const handleSelectSuggestion = (suggestion) => {
    const newLat = parseFloat(suggestion.lat);
    const newLng = parseFloat(suggestion.lon);
    const newAddress = formatAddress(suggestion.display_name);

    lastGeocodedRef.current = coordKey(newLat, newLng);
    setQuery(newAddress);
    setShowSuggestions(false);
    onLocationChange({ address: newAddress, lat: newLat, lng: newLng });
  };

  const handleMapChange = async (newLat, newLng) => {
    // Claim this pair first so the effect above does not geocode it again.
    lastGeocodedRef.current = coordKey(newLat, newLng);
    onLocationChange({ address: query, lat: newLat, lng: newLng });
    // Reverse geocode
    try {
      const formatted = await reverseGeocode(newLat, newLng);
      if (formatted) {
        setQuery(formatted);
        setShowSuggestions(false);
        onLocationChange({ address: formatted, lat: newLat, lng: newLng });
      }
    } catch (err) {
      console.error("Reverse geocoding error:", err);
    }
  };

  return (
    <div className="relative space-y-2">
      <label className="label">{label}</label>

      <div className="relative">
        <input
          type="text"
          className="input w-full"
          placeholder={placeholder}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setShowSuggestions(true);
            // If they clear the text, clear coordinates
            if (!e.target.value) {
              onLocationChange({ address: '', lat: null, lng: null });
            }
          }}
          onFocus={() => {
            if (query.length >= 3) setShowSuggestions(true);
          }}
        />
        {isSearching && (
          <span className="absolute right-3 top-1/2 -translate-y-1/2">
            <svg className="h-4 w-4 animate-spin text-content-faint" viewBox="0 0 24 24" fill="none">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
              <path className="opacity-90" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
          </span>
        )}
      </div>

      {showSuggestions && suggestions.length > 0 && (
        <ul className="absolute z-50 mt-1 max-h-60 w-full overflow-y-auto rounded-lg border border-line bg-surface shadow-overlay">
          {suggestions.map((s, i) => (
            <li key={i}>
              <button
                type="button"
                onClick={() => handleSelectSuggestion(s)}
                className="block w-full px-3.5 py-2.5 text-left text-sm text-content-soft transition-colors hover:bg-surface-raised"
              >
                {s.display_name}
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="overflow-hidden rounded-lg border border-line">
        <LocationPickerMap
          lat={lat}
          lng={lng}
          referenceLat={referenceLat}
          referenceLng={referenceLng}
          referenceLabel={referenceLabel}
          onChange={handleMapChange}
        />
      </div>

      {referenceLat != null && referenceLng != null && (
        <p className="flex items-center gap-1.5 text-xs text-content-faint">
          <span className="inline-block h-2.5 w-2.5 rounded-full bg-[#3b82f6]" />
          {referenceLabel || 'Reference'} location, shown for context
        </p>
      )}

      <p className="text-xs text-content-faint">
        Type an address or click the map. The quoted price is calculated by the server from the
        real road route between the two points.
      </p>
    </div>
  );
}
