'use client';

/**
 * AuthContext
 *
 * Global authentication state for the application.
 * Wraps the component tree with <AuthProvider> so every page/component
 * can call `useAuth()` to access user data and auth actions.
 *
 * On mount the provider checks localStorage for an existing JWT and
 * validates it against GET /users/me.  If the token is stale or invalid
 * the session is silently cleared so the user is redirected to /login.
 */

import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
} from 'react';
import { useRouter } from 'next/navigation';
import { loginUser, registerUser, fetchCurrentUser } from '@/lib/api';

const AuthContext = createContext(undefined);

export function AuthProvider({ children }) {
  const router = useRouter();

  /* ---- state ---- */
  const [user, setUser] = useState(null);       // { email, role } or null
  const [token, setToken] = useState(null);      // JWT string or null
  const [loading, setLoading] = useState(true);  // true until hydration finishes

  /* ---- hydrate session on mount ---- */
  useEffect(() => {
    async function hydrate() {
      const stored = localStorage.getItem('token');

      if (!stored) {
        setLoading(false);
        return;
      }

      try {
        // Validate the persisted token with the backend
        const me = await fetchCurrentUser();
        setUser({ 
          email: me.email, 
          role: me.role, 
          firstName: me.firstName, 
          avatarUrl: me.avatarUrl 
        });
        setToken(stored);
      } catch {
        // Token is expired / invalid — wipe it
        localStorage.removeItem('token');
        setUser(null);
        setToken(null);
      } finally {
        setLoading(false);
      }
    }

    hydrate();
  }, []);

  /* ---- actions ---- */

  const refreshUser = useCallback(async () => {
    try {
      const me = await fetchCurrentUser();
      setUser({ 
        email: me.email, 
        role: me.role, 
        firstName: me.firstName, 
        avatarUrl: me.avatarUrl 
      });
    } catch (err) {
      console.error("Failed to refresh user", err);
    }
  }, []);

  /**
   * Log in with email & password.
   * Persists the JWT and hydrates user state.
   * @returns the raw API response so callers can inspect role, etc.
   */
  const login = useCallback(async (email, password) => {
    const res = await loginUser(email, password);

    localStorage.setItem('token', res.token);
    setToken(res.token);
    setUser({ email: res.email, role: res.role });

    // Instantly hydrate full profile after login
    refreshUser();

    return res;
  }, [refreshUser]);

  /**
   * Register a new account.
   * Does NOT automatically log the user in, as they must verify their email first.
   */
  const register = useCallback(async (email, password, role) => {
    const res = await registerUser(email, password, role);
    return res;
  }, []);

  /**
   * Sign out — clears all client-side auth state and redirects to /login.
   */
  const logout = useCallback(() => {
    localStorage.removeItem('token');
    setUser(null);
    setToken(null);
    router.push('/login');
  }, [router]);

  /* ---- derived ---- */
  const isAuthenticated = !!user && !!token;

  /* ---- provider ---- */
  const value = {
    user,
    token,
    loading,
    login,
    register,
    logout,
    refreshUser,
    isAuthenticated,
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

/**
 * Convenience hook — throws if used outside <AuthProvider>.
 */
export function useAuth() {
  const ctx = useContext(AuthContext);

  if (ctx === undefined) {
    throw new Error('useAuth must be used within an <AuthProvider>');
  }

  return ctx;
}
