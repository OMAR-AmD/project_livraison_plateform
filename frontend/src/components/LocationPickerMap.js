import { useEffect, useState, useRef, useCallback } from 'react';
import { MapContainer, TileLayer, Marker, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

// Fix Leaflet's default icon issue in React
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: '/leaflet/marker-icon-2x.png',
  iconUrl: '/leaflet/marker-icon.png',
  shadowUrl: '/leaflet/marker-shadow.png',
});
// Note: We use public URLs, or we can just rely on standard unpkg URLs if the local ones fail.
// Since we have leaflet-defaulticon-compatibility installed, we might not need this, but we'll use a custom svg icon to be safe.

const customIcon = L.divIcon({
  className: 'custom-pin-icon',
  html: `<svg width="24" height="36" viewBox="0 0 24 36" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path d="M12 0C5.372 0 0 5.373 0 12c0 8.442 11.234 23.385 11.606 23.864a.5.5 0 00.788 0C12.766 35.385 24 20.442 24 12c0-6.627-5.372-12-12-12zm0 17.5a5.5 5.5 0 110-11 5.5 5.5 0 010 11z" fill="#ef4444"/>
  </svg>`,
  iconSize: [24, 36],
  iconAnchor: [12, 36],
  popupAnchor: [0, -36]
});

function MapEvents({ onMapClick }) {
  useMapEvents({
    click(e) {
      onMapClick(e.latlng);
    },
  });
  return null;
}

export default function LocationPickerMap({ lat, lng, onChange }) {
  const [map, setMap] = useState(null);

  // If external lat/lng changes drastically, pan the map
  useEffect(() => {
    if (map && lat && lng) {
      map.flyTo([lat, lng], map.getZoom() > 14 ? map.getZoom() : 15);
    }
  }, [lat, lng, map]);

  return (
    <div className="h-48 w-full rounded-lg overflow-hidden border border-line z-0">
      <MapContainer
        center={[lat || 33.5731, lng || -7.5898]}
        zoom={13}
        style={{ height: '100%', width: '100%' }}
        ref={setMap}
        attributionControl={false}
      >
        <TileLayer
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <MapEvents onMapClick={(latlng) => onChange(latlng.lat, latlng.lng)} />
        {lat && lng && (
          <Marker 
            position={[lat, lng]} 
            icon={customIcon}
            draggable={true}
            eventHandlers={{
              dragend: (e) => {
                const marker = e.target;
                const position = marker.getLatLng();
                onChange(position.lat, position.lng);
              },
            }}
          />
        )}
      </MapContainer>
    </div>
  );
}
