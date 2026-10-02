import { useEffect, useState, useRef } from 'react';
import { MapContainer, Marker, Polyline, useMap, Tooltip } from 'react-leaflet';
import BasemapLayer from './BasemapLayer';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

// Fix for default Leaflet icon paths in Next.js
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon-2x.png',
  iconUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon.png',
  shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-shadow.png',
});

const createIcon = (color) => L.divIcon({
  className: 'custom-pin-icon',
  html: `<svg width="24" height="36" viewBox="0 0 24 36" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path d="M12 0C5.372 0 0 5.373 0 12c0 8.442 11.234 23.385 11.606 23.864a.5.5 0 00.788 0C12.766 35.385 24 20.442 24 12c0-6.627-5.372-12-12-12zm0 17.5a5.5 5.5 0 110-11 5.5 5.5 0 010 11z" fill="${color}"/>
  </svg>`,
  iconSize: [24, 36],
  iconAnchor: [12, 36],
  popupAnchor: [0, -36]
});

const pickupIcon = createIcon('#22c55e'); // green
const dropoffIcon = createIcon('#ef4444'); // red
const courierIcon = createIcon('#3b82f6'); // blue

// Component to auto-fit map bounds to markers ONCE
const MapBounds = ({ waypoints, courierPos }) => {
  const map = useMap();
  const hasFittedRef = useRef(false);

  useEffect(() => {
    if (hasFittedRef.current) return;
    // Fit as soon as there is anything to show. The courier may not have
    // broadcast yet -- the client map is visible from the ASSIGNED state -- and
    // in that case centring on the pickup/drop-off still beats leaving the
    // default Casablanca view.
    const pts = [];
    if (courierPos) pts.push([courierPos.lat, courierPos.lng]);
    if (waypoints) waypoints.forEach(wp => pts.push([wp.latitude, wp.longitude]));
    if (pts.length > 0) {
      map.fitBounds(L.latLngBounds(pts), { padding: [50, 50], maxZoom: 16 });
      hasFittedRef.current = true;
    }
  }, [map, waypoints, courierPos]);
  return null;
};

export default function OptimizedRouteMap({ orderedWaypoints, courierPos, height = '500px' }) {
  const [routeCoordinates, setRouteCoordinates] = useState([]);

  const hasFetchedRef = useRef(false);

  useEffect(() => {
    if (orderedWaypoints && orderedWaypoints.length > 0 && courierPos && !hasFetchedRef.current) {
      hasFetchedRef.current = true;
      // Build OSRM route coordinate string
      // Starts with initial courier position, then ordered waypoints
      let coordsString = `${courierPos.lng},${courierPos.lat};`;
      coordsString += orderedWaypoints.map(wp => `${wp.longitude},${wp.latitude}`).join(';');

      fetch(`/osrm/route/v1/driving/${coordsString}?overview=full&geometries=geojson`)
        .then(res => res.json())
        .then(data => {
          if (data.routes && data.routes.length > 0) {
            // Convert GeoJSON [lng, lat] to Leaflet [lat, lng]
            const coords = data.routes[0].geometry.coordinates.map(c => [c[1], c[0]]);
            setRouteCoordinates(coords);
          }
        })
        .catch(err => {
          console.error("OSRM error:", err);
          hasFetchedRef.current = false; // allow retry on error
        });
    }
  }, [orderedWaypoints, courierPos]);

  const points = [];
  if (courierPos) points.push([courierPos.lat, courierPos.lng]);
  if (orderedWaypoints) {
    orderedWaypoints.forEach(wp => points.push([wp.latitude, wp.longitude]));
  }

  const center = points.length > 0 ? points[0] : [33.5731, -7.5898]; // Default

  return (
    <div style={{ height, width: '100%' }}>
      <MapContainer center={center} zoom={13} style={{ height: '100%', width: '100%', borderRadius: '0.5rem' }}>
        <BasemapLayer />
        
        {orderedWaypoints && orderedWaypoints.length > 0 && (
          <MapBounds waypoints={orderedWaypoints} courierPos={courierPos} />
        )}

        {courierPos && (
          <Marker position={[courierPos.lat, courierPos.lng]} icon={courierIcon}>
            <Tooltip permanent direction="top" offset={[0, -40]}>Courier</Tooltip>
          </Marker>
        )}

        {orderedWaypoints && orderedWaypoints.map((wp, idx) => (
          <Marker 
            key={`${wp.deliveryId}-${wp.type}-${idx}`} 
            position={[wp.latitude, wp.longitude]} 
            icon={wp.type === 'PICKUP' ? pickupIcon : dropoffIcon}
          >
            <Tooltip permanent direction="top" offset={[0, -40]}>
              <div className="text-center font-bold text-gray-800">
                {wp.type === 'PICKUP' ? 'Pickup' : 'Dropoff'}
              </div>
            </Tooltip>
          </Marker>
        ))}

        {routeCoordinates.length > 0 && (
          <Polyline 
            positions={routeCoordinates} 
            // Leaflet takes a raw CSS colour, so the palette value is inlined here.
            // signal-500 (#F97316) is the platform accent.
            color="#F97316"
            weight={5} 
            opacity={0.85} 
          />
        )}
      </MapContainer>
    </div>
  );
}
