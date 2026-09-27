import { useState, useEffect, useRef } from 'react';
import dynamic from 'next/dynamic';

const LocationPickerMap = dynamic(() => import('./LocationPickerMap'), { ssr: false });

export default function LocationPicker({ label, placeholder, address, lat, lng, onLocationChange }) {
  const [query, setQuery] = useState(address || '');
  const [suggestions, setSuggestions] = useState([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [isSearching, setIsSearching] = useState(false);
  const timeoutRef = useRef(null);

  // Sync external address changes
  useEffect(() => {
    if (address !== query && !showSuggestions) {
      setQuery(address || '');
    }
  }, [address]);

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
    const newAddress = suggestion.display_name;
    
    setQuery(newAddress);
    setShowSuggestions(false);
    onLocationChange({ address: newAddress, lat: newLat, lng: newLng });
  };

  const handleMapChange = async (newLat, newLng) => {
    onLocationChange({ address: query, lat: newLat, lng: newLng });
    // Reverse geocode
    try {
      const res = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${newLat}&lon=${newLng}`);
      const data = await res.json();
      if (data && data.display_name) {
        setQuery(data.display_name);
        setShowSuggestions(false);
        onLocationChange({ address: data.display_name, lat: newLat, lng: newLng });
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
        <LocationPickerMap lat={lat} lng={lng} onChange={handleMapChange} />
      </div>

      <p className="text-xs text-content-faint">
        Type an address or click the map. The quoted price is calculated by the server from the
        real road route between the two points.
      </p>
    </div>
  );
}
