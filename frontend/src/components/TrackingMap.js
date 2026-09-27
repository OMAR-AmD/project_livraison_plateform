import { useEffect, useState } from 'react';
import { MapContainer, TileLayer, Marker, Polyline, useMap, Tooltip } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

// Fix for default Leaflet icon paths in Next.js
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon-2x.png',
  iconUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon.png',
  shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-shadow.png',
});

// Custom Icons
const createIcon = (color) => L.divIcon({
  className: 'custom-pin-icon',
  html: `<svg width="24" height="36" viewBox="0 0 24 36" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path d="M12 0C5.372 0 0 5.373 0 12c0 8.442 11.234 23.385 11.606 23.864a.5.5 0 00.788 0C12.766 35.385 24 20.442 24 12c0-6.627-5.372-12-12-12zm0 17.5a5.5 5.5 0 110-11 5.5 5.5 0 010 11z" fill="${color}"/>
  </svg>`,
  iconSize: [24, 36],
  iconAnchor: [12, 36],
  popupAnchor: [0, -36]
});

const pickupIcon = createIcon('#22c55e');
const dropoffIcon = createIcon('#ef4444');
const courierIcon = createIcon('#3b82f6');

// Component to auto-fit map bounds to markers
const MapBounds = ({ points }) => {
  const map = useMap();
  useEffect(() => {
    if (points.length > 0) {
      const bounds = L.latLngBounds(points);
      map.fitBounds(bounds, { padding: [50, 50], maxZoom: 16 });
    }
  }, [map, points]);
  return null;
};

export default function TrackingMap({ pickupLat, pickupLng, dropoffLat, dropoffLng, courierPos }) {
  const [routeCoordinates, setRouteCoordinates] = useState([]);
  
  const hasPickup = pickupLat && pickupLng;
  const hasDropoff = dropoffLat && dropoffLng;

  useEffect(() => {
    if (hasPickup && hasDropoff) {
      // Build coordinates string
      let coordsString = `${pickupLng},${pickupLat};${dropoffLng},${dropoffLat}`;
      if (courierPos) {
        coordsString = `${courierPos.lng},${courierPos.lat};${coordsString}`;
      }

      // Fetch OSRM route
      fetch(`/osrm/route/v1/driving/${coordsString}?overview=simplified&geometries=geojson`)
        .then(res => res.json())
        .then(data => {
          if (data.routes && data.routes.length > 0) {
            // Convert GeoJSON [lng, lat] to Leaflet [lat, lng]
            const coords = data.routes[0].geometry.coordinates.map(c => [c[1], c[0]]);
            setRouteCoordinates(coords);
          } else {
            setRouteCoordinates(courierPos 
              ? [[courierPos.lat, courierPos.lng], [pickupLat, pickupLng], [dropoffLat, dropoffLng]]
              : [[pickupLat, pickupLng], [dropoffLat, dropoffLng]]); // Fallback
          }
        })
        .catch(err => {
          console.error("OSRM error:", err);
          setRouteCoordinates(courierPos 
            ? [[courierPos.lat, courierPos.lng], [pickupLat, pickupLng], [dropoffLat, dropoffLng]]
            : [[pickupLat, pickupLng], [dropoffLat, dropoffLng]]); // Fallback
        });
    }
  }, [pickupLat, pickupLng, dropoffLat, dropoffLng, courierPos?.lat, courierPos?.lng]);

  const points = [];
  if (hasPickup) points.push([pickupLat, pickupLng]);
  if (hasDropoff) points.push([dropoffLat, dropoffLng]);
  if (courierPos) points.push([courierPos.lat, courierPos.lng]);

  const center = points.length > 0 ? points[0] : [33.5731, -7.5898]; // Default Casablanca, Morocco

  return (
    <div style={{ height: '400px', width: '100%' }}>
      <MapContainer center={center} zoom={13} style={{ height: '100%', width: '100%', borderRadius: '0.5rem' }}>
        <TileLayer
          attribution='&copy; <a href="https://openstreetmap.org/">OpenStreetMap</a>'
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        
        {points.length > 1 && <MapBounds points={points} />}

        {hasPickup && (
          <Marker position={[pickupLat, pickupLng]} icon={pickupIcon}>
            <Tooltip permanent direction="top" offset={[0, -40]}>
              <div className="text-center font-semibold text-gray-800 text-xs">Pickup</div>
            </Tooltip>
          </Marker>
        )}
        
        {hasDropoff && (
          <Marker position={[dropoffLat, dropoffLng]} icon={dropoffIcon}>
            <Tooltip permanent direction="top" offset={[0, -40]}>
              <div className="text-center font-semibold text-gray-800 text-xs">Dropoff</div>
            </Tooltip>
          </Marker>
        )}
        
        {courierPos && (
          <Marker position={[courierPos.lat, courierPos.lng]} icon={courierIcon}>
            <Tooltip permanent direction="top" offset={[0, -40]}>
              <div className="text-center font-semibold text-gray-800 text-xs">Courier</div>
            </Tooltip>
          </Marker>
        )}

        {routeCoordinates.length > 0 && (
          <Polyline 
            positions={routeCoordinates} 
            color="#3b82f6" 
            weight={4} 
            dashArray={routeCoordinates.length === 2 ? "10, 10" : null} // Dashed if straight-line fallback
            opacity={0.7} 
          />
        )}
      </MapContainer>
    </div>
  );
}
