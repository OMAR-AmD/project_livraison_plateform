'use client';

import { useAuth } from '@/context/AuthContext';
import ClientView from './ClientView';
import CourierView from './CourierView';
import AdminView from './AdminView';

export default function DeliveriesPage() {
  const { user } = useAuth();

  // Show nothing or a loader until user is hydrated
  if (!user) {
    return <div className="text-center text-content-faint mt-20">Loading deliveries...</div>;
  }

  // Render the appropriate view based on role
  if (user.role === 'ADMIN') return <AdminView />;
  if (user.role === 'LIVREUR') return <CourierView />;
  
  // Default to client view
  return <ClientView />;
}
