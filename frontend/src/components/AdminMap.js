import { useEffect, useState, useRef } from 'react';
import { MapContainer, TileLayer, Marker, Tooltip, useMap, Polyline } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
// import { Client } from '@stomp/stompjs';
// import SockJS from 'sockjs-client';
import { adminGetCourierLocation, adminGetCourierRoute } from '@/lib/api';

// Fix for default Leaflet icon paths in Next.js
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon-2x.png',
  iconUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon.png',
  shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-shadow.png',
});

// Create distinct colored icons for different couriers
const colors = ['#3b82f6', '#f97316', '#8b5cf6', '#eab308', '#06b6d4', '#ec4899']; // Removed green and red, added cyan and pink
const leafletColors = ['blue', 'orange', 'violet', 'gold', 'cyan', 'pink'];

const getCourierHash = (email) => {
  if (!email) return 0;
  let hash = 0;
  for (let i = 0; i < email.length; i++) hash = email.charCodeAt(i) + ((hash << 5) - hash);
  return Math.abs(hash);
};

const getCourierHexColor = (email) => colors[getCourierHash(email) % colors.length];
const getCourierLeafletColor = (email) => leafletColors[getCourierHash(email) % leafletColors.length];

const createIcon = (color) => L.divIcon({
  className: 'custom-pin-icon',
  html: `<svg width="24" height="36" viewBox="0 0 24 36" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path d="M12 0C5.372 0 0 5.373 0 12c0 8.442 11.234 23.385 11.606 23.864a.5.5 0 00.788 0C12.766 35.385 24 20.442 24 12c0-6.627-5.372-12-12-12zm0 17.5a5.5 5.5 0 110-11 5.5 5.5 0 010 11z" fill="${color}"/>
  </svg>`,
  iconSize: [24, 36],
  iconAnchor: [12, 36],
  popupAnchor: [0, -36]
});

// Component to auto-fit map bounds
const GlobalMapBounds = ({ deliveries }) => {
  const map = useMap();
  const hasFittedRef = useRef(false);

  useEffect(() => {
    if (deliveries && deliveries.length > 0 && !hasFittedRef.current) {
      const pts = [];
      deliveries.forEach(d => {
        if (d.pickupLat && d.pickupLng) pts.push([d.pickupLat, d.pickupLng]);
        if (d.dropoffLat && d.dropoffLng) pts.push([d.dropoffLat, d.dropoffLng]);
      });
      if (pts.length > 0) {
        const bounds = L.latLngBounds(pts);
        map.fitBounds(bounds, { padding: [50, 50], maxZoom: 14 });
        hasFittedRef.current = true;
      }
    }
  }, [map, deliveries]);
  return null;
};

export default function AdminMap({ deliveries }) {
  const [courierPositions, setCourierPositions] = useState({});
  const [courierRoutes, setCourierRoutes] = useState({});

  useEffect(() => {
    const activeDeliveries = deliveries.filter(d => d.status === 'IN_TRANSIT');
    if (activeDeliveries.length === 0) return;

    // We only want to fetch initial location and route ONCE per courier
    const courierMap = {};
    activeDeliveries.forEach(d => {
      if (d.courierEmail && d.courierId && !courierMap[d.courierEmail]) {
        courierMap[d.courierEmail] = d.courierId;
      }
    });

    const initCouriers = async () => {
      for (const [email, courierId] of Object.entries(courierMap)) {
        // Find one delivery ID for this courier to fetch their initial location
        const deliveryForCourier = activeDeliveries.find(d => d.courierEmail === email);
        if (!deliveryForCourier) continue;

        let loc = null;
        try {
          loc = await adminGetCourierLocation(deliveryForCourier.id);
          if (loc && loc.latitude) {
            setCourierPositions(prev => ({
              ...prev,
              [email]: { lat: loc.latitude, lng: loc.longitude }
            }));
          }
        } catch (err) {}

        // Fallback to the first pickup location if courier hasn't broadcasted yet
        const startLat = (loc && loc.latitude) ? loc.latitude : deliveryForCourier.pickupLat;
        const startLng = (loc && loc.longitude) ? loc.longitude : deliveryForCourier.pickupLng;

        // If we found a location, try to fetch the optimized route for drawing!
        if (startLat && startLng) {
          try {
            const optData = await adminGetCourierRoute(courierId, startLat, startLng);
            if (optData && optData.orderedWaypoints && optData.orderedWaypoints.length > 0) {
              let coordsString = `${startLng},${startLat};`;
              coordsString += optData.orderedWaypoints.map(wp => `${wp.longitude},${wp.latitude}`).join(';');
              
              const osrmRes = await fetch(`/osrm/route/v1/driving/${coordsString}?overview=full&geometries=geojson`);
              const osrmData = await osrmRes.json();
              if (osrmData.routes && osrmData.routes.length > 0) {
                const path = osrmData.routes[0].geometry.coordinates.map(c => [c[1], c[0]]);
                setCourierRoutes(prev => ({ ...prev, [email]: path }));
              }
            }
          } catch (err) {
            console.error("Failed to fetch route for", email, err);
          }
        }
      }
    };
    initCouriers();

    // Poll positions every 2 seconds instead of STOMP
    const pollInterval = setInterval(() => {
      activeDeliveries.forEach(async (d) => {
        if (!d.courierEmail) return;
        try {
          const loc = await adminGetCourierLocation(d.id);
          if (loc && loc.latitude) {
            setCourierPositions(prev => ({
              ...prev,
              [d.courierEmail]: { lat: loc.latitude, lng: loc.longitude }
            }));
          }
        } catch (err) {}
      });
    }, 2000);

    return () => {
      clearInterval(pollInterval);
    };
  }, [deliveries]);

  const activeCouriers = Object.keys(courierPositions);

  return (
    <div style={{ height: '600px', width: '100%' }} className="rounded-xl overflow-hidden border border-line mb-6">
      <MapContainer center={[33.5731, -7.5898]} zoom={12} style={{ height: '100%', width: '100%' }}>
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        
        <GlobalMapBounds deliveries={deliveries.filter(d => d.status === 'IN_TRANSIT' || d.status === 'ASSIGNED')} />

        {/* Render Routes */}
        {Object.entries(courierRoutes).map(([email, path]) => (
          <Polyline 
            key={`route-${email}`} 
            positions={path} 
            pathOptions={{ color: getCourierHexColor(email), weight: 5, opacity: 0.7 }} 
          />
        ))}

        {/* Render Deliveries (Pickups and Dropoffs) */}
        {deliveries.filter(d => d.status === 'IN_TRANSIT' || d.status === 'ASSIGNED').map((d) => {
          
          return (
            <div key={`del-${d.id}`}>
              {d.pickupLat && d.pickupLng && (
                <Marker position={[d.pickupLat, d.pickupLng]} icon={createIcon('#22c55e')}>
                  <Tooltip direction="top">
                    <div className="text-center font-semibold text-gray-800">
                      Pickup: {d.description}<br/>
                      <span className="text-xs font-normal text-gray-600">Courier: {d.courierEmail || 'Unassigned'}</span>
                    </div>
                  </Tooltip>
                </Marker>
              )}
              {d.dropoffLat && d.dropoffLng && (
                <Marker position={[d.dropoffLat, d.dropoffLng]} icon={createIcon('#ef4444')}>
                  <Tooltip direction="top">
                    <div className="text-center font-semibold text-gray-800">
                      Dropoff: {d.description}
                    </div>
                  </Tooltip>
                </Marker>
              )}
            </div>
          );
        })}

        {/* Render live Couriers */}
        {activeCouriers.map((email) => {
          const pos = courierPositions[email];
          const courierHexColor = getCourierHexColor(email);
          const icon = createIcon(courierHexColor);

          return (
            <Marker key={`courier-${email}`} position={[pos.lat, pos.lng]} icon={icon} zIndexOffset={1000}>
              <Tooltip permanent direction="bottom" offset={[0, 10]}>
                <div className="font-bold flex items-center gap-2">
                  <span className="relative flex h-3 w-3">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-3 w-3 bg-green-500"></span>
                  </span>
                  {email}
                </div>
              </Tooltip>
            </Marker>
          );
        })}

      </MapContainer>
    </div>
  );
}
