'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import { useToast } from '@/components/Toast';
import AuthShell from '@/components/AuthShell';

const ACCOUNT_TYPES = [
  {
    value: 'CLIENT',
    label: 'Client',
    caption: 'Book and track deliveries',
  },
  {
    value: 'LIVREUR',
    label: 'Courier',
    caption: 'Accept and deliver rounds',
  },
];

export default function RegisterPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [role, setRole] = useState('CLIENT');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const { register, isAuthenticated, loading: authLoading } = useAuth();
  const { addToast } = useToast();
  const router = useRouter();

  useEffect(() => {
    if (!authLoading && isAuthenticated) {
      router.push('/dashboard');
    }
  }, [isAuthenticated, authLoading, router]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');

    if (password.length < 8) {
      setError('Password must be at least 8 characters long.');
      return;
    }
    if (password !== confirmPassword) {
      setError('The passwords do not match.');
      return;
    }

    setLoading(true);
    try {
      await register(email.trim(), password, role);
      addToast('Account created. Please sign in.', 'success');
      router.push('/login');
    } catch (err) {
      setError(err.message || 'Unable to create the account');
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthShell
      title="Create an account"
      subtitle="Self-service signup for clients and couriers."
      footer={
        <>
          Already have an account?{' '}
          <Link href="/login" className="link">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        {error && (
          <div
            role="alert"
            className="rounded-md border border-danger-500/25 bg-danger-500/10 px-3.5 py-3 text-sm text-danger-300"
          >
            {error}
          </div>
        )}

        <fieldset>
          <legend className="label">Account type</legend>
          <div className="grid grid-cols-2 gap-3">
            {ACCOUNT_TYPES.map((type) => (
              <button
                key={type.value}
                type="button"
                onClick={() => setRole(type.value)}
                aria-pressed={role === type.value}
                className={`rounded-md border px-3 py-3 text-left transition-colors ${
                  role === type.value
                    ? 'border-signal-500 bg-signal-500/10'
                    : 'border-line bg-surface-sunken hover:border-line-strong'
                }`}
              >
                <span
                  className={`block text-sm font-semibold ${
                    role === type.value ? 'text-signal-400' : 'text-content'
                  }`}
                >
                  {type.label}
                </span>
                <span className="mt-0.5 block text-xs text-content-faint">{type.caption}</span>
              </button>
            ))}
          </div>
          <p className="mt-2 text-xs text-content-faint">
            Administrator access is granted separately and cannot be self-selected.
          </p>
        </fieldset>

        <div>
          <label htmlFor="register-email" className="label">
            Email address
          </label>
          <input
            id="register-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@company.com"
            className="input"
            required
            autoComplete="email"
          />
        </div>

        <div>
          <label htmlFor="register-password" className="label">
            Password
          </label>
          <input
            id="register-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="At least 8 characters"
            className="input"
            required
            minLength={8}
            autoComplete="new-password"
          />
        </div>

        <div>
          <label htmlFor="register-confirm-password" className="label">
            Confirm password
          </label>
          <input
            id="register-confirm-password"
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            placeholder="Repeat your password"
            className="input"
            required
            autoComplete="new-password"
          />
        </div>

        <button type="submit" disabled={loading} className="btn-primary w-full">
          {loading ? 'Creating account…' : 'Create account'}
        </button>
      </form>
    </AuthShell>
  );
}
