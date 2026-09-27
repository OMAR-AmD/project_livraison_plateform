'use client';

import { useState, useEffect } from 'react';
import { getNotifications, markNotificationAsRead, markAllNotificationsAsRead } from '@/lib/api';
import { useToast } from '@/components/Toast';

export default function NotificationsPage() {
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(true);
  const { addToast } = useToast();

  useEffect(() => {
    fetchNotifications();
  }, []);

  const fetchNotifications = async () => {
    try {
      const data = await getNotifications();
      setNotifications(data);
    } catch (err) {
      addToast('Failed to load notifications', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleMarkAsRead = async (id) => {
    try {
      await markNotificationAsRead(id);
      setNotifications((prev) =>
        prev.map((n) => (n.id === id ? { ...n, isRead: true } : n))
      );
      // Instantly tell the Sidebar to update its badge
      window.dispatchEvent(new Event('notificationsUpdated'));
    } catch (err) {
      addToast('Failed to mark as read', 'error');
    }
  };

  const handleMarkAllAsRead = async () => {
    try {
      await markAllNotificationsAsRead();
      setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true })));
      // Instantly tell the Sidebar to update its badge
      window.dispatchEvent(new Event('notificationsUpdated'));
      addToast('All notifications marked as read', 'success');
    } catch (err) {
      addToast('Failed to mark all as read', 'error');
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <div className="animate-spin rounded-full h-12 w-12 border-t-2 border-b-2 border-info-500"></div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto animate-fadeIn">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold text-content mb-2">Notifications</h1>
          <p className="text-content-muted">Stay updated on your deliveries</p>
        </div>
        
        {notifications.some(n => !n.isRead) && (
          <button
            onClick={handleMarkAllAsRead}
            className="text-sm font-medium text-info-400 hover:text-info-300 transition-colors flex items-center gap-2 bg-info-500/10 px-4 py-2 rounded-lg"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
            </svg>
            Mark all as read
          </button>
        )}
      </div>

      {notifications.length === 0 ? (
        <div className="surface p-12 text-center">
          <div className="bg-surface-raised w-16 h-16 rounded-full flex items-center justify-center mx-auto mb-4">
            <svg className="w-8 h-8 text-content-faint" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
            </svg>
          </div>
          <h3 className="text-lg font-medium text-content mb-1">No notifications yet</h3>
          <p className="text-content-muted">When you receive updates, they will appear here.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {notifications.map((notification) => (
            <div
              key={notification.id}
              className={`flex gap-4 rounded-lg border p-4 transition-colors ${
                notification.isRead
                  ? 'border-line-soft bg-surface opacity-70'
                  : 'border-line bg-surface-raised'
              }`}
            >
              <div className="mt-1">
                <div className={`w-2.5 h-2.5 rounded-full ${notification.isRead ? 'bg-content-faint' : 'bg-signal-500 animate-pulse'}`} />
              </div>
              <div className="flex-1">
                <p className={`text-sm md:text-base ${notification.isRead ? 'text-content-soft' : 'text-content font-medium'}`}>
                  {notification.message}
                </p>
                <div className="flex items-center justify-between mt-3">
                  <span className="text-xs text-content-faint">
                    {new Date(notification.createdAt).toLocaleString()}
                  </span>
                  {!notification.isRead && (
                    <button
                      onClick={() => handleMarkAsRead(notification.id)}
                      className="text-xs font-medium text-info-400 hover:text-info-300 transition-colors"
                    >
                      Mark as read
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
