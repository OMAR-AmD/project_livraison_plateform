'use client';

import { useState, useEffect } from 'react';
import { fetchCurrentUser, updateProfile } from '@/lib/api';
import { useAuth } from '@/context/AuthContext';

export default function ProfilePage() {
  const { user, refreshUser } = useAuth();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);
  const [formData, setFormData] = useState({
    firstName: '',
    lastName: '',
    phoneNumber: '',
    defaultAddress: '',
    avatarUrl: ''
  });
  const [imgError, setImgError] = useState(false);

  useEffect(() => {
    async function loadProfile() {
      try {
        const data = await fetchCurrentUser();
        setFormData({
          firstName: data.firstName || '',
          lastName: data.lastName || '',
          phoneNumber: data.phoneNumber || '',
          defaultAddress: data.defaultAddress || '',
          avatarUrl: data.avatarUrl || ''
        });
      } catch (err) {
        setError(err.message || 'Failed to load profile');
      } finally {
        setLoading(false);
      }
    }
    loadProfile();
  }, []);

  const handleChange = (e) => {
    setFormData(prev => ({ ...prev, [e.target.name]: e.target.value }));
    // Clear alerts on type
    setError(null);
    setSuccess(false);
    if (e.target.name === 'avatarUrl') {
      setImgError(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSuccess(false);

    try {
      await updateProfile(formData);
      await refreshUser(); // Update global state
      setSuccess(true);
    } catch (err) {
      setError(err.message || 'Failed to update profile');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-info-500"></div>
      </div>
    );
  }

  return (
    <div className="max-w-3xl mx-auto py-8">
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-content mb-2 tracking-tight">Profile Settings</h1>
        <p className="text-content-muted text-sm">Manage your personal information and preferences.</p>
      </div>

      <div className="surface relative overflow-hidden">
        {/* Subtle decorative glow in the accent colour */}
        <div className="pointer-events-none absolute -right-16 -top-16 h-64 w-64 rounded-full bg-signal-500/[0.07] blur-3xl" />
        
        <form onSubmit={handleSubmit} className="p-8 space-y-6 relative">
          
          <div className="flex items-center gap-6 pb-6 border-b border-line-soft">
            <div className="relative w-24 h-24 rounded-full bg-surface-raised border border-line flex items-center justify-center overflow-hidden shrink-0 shadow-lg shadow-black/20">
              {formData.avatarUrl && !imgError ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img 
                  src={formData.avatarUrl} 
                  alt="Profile Avatar" 
                  className="w-full h-full object-cover"
                  onError={() => setImgError(true)} 
                />
              ) : (
                <div className="w-full h-full bg-signal-500/15 text-signal-400 flex items-center justify-center text-3xl font-bold">
                  {formData.firstName ? formData.firstName.charAt(0).toUpperCase() : user?.email?.charAt(0).toUpperCase() || 'U'}
                </div>
              )}
            </div>
            <div>
              <h3 className="text-lg font-medium text-content tracking-tight">Profile Picture</h3>
              <p className="text-sm text-content-muted mt-1">Add an image URL below to customize your avatar.</p>
              {imgError && <p className="text-xs text-danger-400 mt-1">Invalid image URL. Showing default avatar.</p>}
            </div>
          </div>

          {error && (
            <div className="p-4 bg-danger-500/10 border border-danger-500/25 rounded-xl text-danger-400 text-sm flex items-center gap-2">
              <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
              {error}
            </div>
          )}

          {success && (
            <div className="p-4 bg-ok-500/10 border border-ok-500/25 rounded-xl text-ok-400 text-sm flex items-center gap-2">
              <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              Profile updated successfully!
            </div>
          )}

          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="space-y-1.5">
              <label className="block text-sm font-medium text-content-soft">First Name</label>
              <input
                type="text"
                name="firstName"
                value={formData.firstName}
                onChange={handleChange}
                className="input"
                placeholder="John"
              />
            </div>
            
            <div className="space-y-1.5">
              <label className="block text-sm font-medium text-content-soft">Last Name</label>
              <input
                type="text"
                name="lastName"
                value={formData.lastName}
                onChange={handleChange}
                className="input"
                placeholder="Doe"
              />
            </div>

            <div className="space-y-1.5">
              <label className="block text-sm font-medium text-content-soft">Phone Number</label>
              <input
                type="tel"
                name="phoneNumber"
                value={formData.phoneNumber}
                onChange={handleChange}
                className="input"
                placeholder="+1 234 567 890"
              />
            </div>

            <div className="space-y-1.5">
              <label className="block text-sm font-medium text-content-soft flex justify-between">
                Email Address 
                <span className="text-xs text-info-400 bg-info-500/10 px-2 py-0.5 rounded-full border border-info-500/25">Read-only</span>
              </label>
              <input
                type="email"
                value={user?.email || ''}
                readOnly
                className="input opacity-60 cursor-not-allowed bg-black/20"
              />
            </div>

            <div className="space-y-1.5 md:col-span-2">
              <label className="block text-sm font-medium text-content-soft">Avatar URL (Optional)</label>
              <input
                type="url"
                name="avatarUrl"
                value={formData.avatarUrl}
                onChange={handleChange}
                className="input"
                placeholder="https://example.com/avatar.png"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <label className="block text-sm font-medium text-content-soft">Default Address</label>
            <textarea
              name="defaultAddress"
              value={formData.defaultAddress}
              onChange={handleChange}
              rows={3}
              className="input resize-none"
              placeholder="123 Main St, City, Country"
            />
          </div>

          <div className="flex justify-end pt-4 border-t border-line-soft">
            <button
              type="submit"
              disabled={saving}
              className="btn-primary"
            >
              {saving ? (
                <div className="flex items-center gap-2">
                  <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                  Saving...
                </div>
              ) : (
                'Save Changes'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
