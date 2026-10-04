/**
 * Centralized API Service
 *
 * Provides a single fetch wrapper (`apiFetch`) that automatically:
 *   – attaches the JWT token from localStorage
 *   – sets JSON content-type headers
 *   – parses responses and converts errors into typed `ApiError` instances
 *
 * All backend calls should go through this module so auth & error handling
 * stay consistent across the entire app.
 */

const API_BASE = '/api/v1';

/**
 * Custom error class for API failures.
 * Carries the HTTP status code and a machine-readable `error` label
 * alongside the human-readable `message`.
 */
class ApiError extends Error {
  constructor(status, error, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.error = error;
  }
}

/**
 * Core fetch wrapper.
 *
 * @param {string}  endpoint  – path relative to API_BASE, e.g. '/auth/login'
 * @param {object}  options   – standard fetch options (method, body, headers …)
 * @returns {Promise<object|null>} parsed JSON body, or null for 204 responses
 * @throws {ApiError} when the server returns a non-2xx status
 */
async function apiFetch(endpoint, options = {}) {
  // Grab the persisted JWT (only on the client side)
  const token =
    typeof window !== 'undefined' ? localStorage.getItem('token') : null;

  const headers = {
    'Content-Type': 'application/json',
    ...options.headers,
  };

  // Attach the bearer token when available
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    headers,
  });

  // 204 No Content – nothing to parse
  if (res.status === 204) return null;

  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch (err) {
    // Fallback if the backend returns a plain string instead of JSON
    data = { message: text };
  }

  if (!res.ok) {
    throw new ApiError(
      data.status || res.status,
      data.error || 'Error',
      data.message || 'Something went wrong',
    );
  }

  return data;
}

/* ------------------------------------------------------------------ */
/*  Auth endpoints                                                     */
/* ------------------------------------------------------------------ */

/**
 * Authenticate an existing user.
 * @returns {{ token: string, email: string, role: string }}
 */
export async function loginUser(email, password) {
  return apiFetch('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

/**
 * Create a new account.
 * @returns {{ token: string, email: string, role: string }}
 */
export async function registerUser(email, password, role) {
  return apiFetch('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, role }),
  });
}

/**
 * Ask the server to revoke every token issued to this account.
 *
 * Clearing localStorage is not enough on its own: a token that has already left
 * the browser stays valid until it expires. This bumps the account's session
 * generation so the copy in the wild is refused. Best-effort by design — the
 * caller clears local state regardless, because a failed revocation must not
 * leave the user stuck in a signed-in shell.
 */
export async function logoutUser() {
  return apiFetch('/auth/logout', { method: 'POST' });
}

/**
 * Fetch the currently authenticated user profile.
 * Requires a valid token in localStorage.
 * @returns {{ email: string, role: string }}
 */
export async function fetchCurrentUser() {
  return apiFetch('/users/me');
}

/**
 * Update the authenticated user's profile.
 * @param {object} profileData - { firstName, lastName, phoneNumber, defaultAddress, avatarUrl }
 * @returns {object} Updated profile
 */
export async function updateProfile(profileData) {
  return apiFetch('/users/me', {
    method: 'PUT',
    body: JSON.stringify(profileData),
  });
}

/* ------------------------------------------------------------------ */
/*  Delivery endpoints                                                */
/* ------------------------------------------------------------------ */

// --- CLIENT ---
export async function clientGetDeliveries() {
  return apiFetch('/client/deliveries');
}

export async function clientCreateDelivery(data) {
  return apiFetch('/client/deliveries', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/**
 * Ask the server to price a delivery without creating it.
 *
 * The returned price is authoritative: the server recomputes it on creation and
 * ignores any figure sent by the client, so the number shown at checkout is the
 * number that gets charged. Replaces the previous client-side haversine estimate,
 * which could disagree with the stored price.
 *
 * @returns {{ distanceKm, durationSeconds, price, exceedsTimeLimit, maxDurationSeconds }}
 */
export async function clientQuoteDelivery(data) {
  return apiFetch('/client/deliveries/quote', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/**
 * Capture payment for a created order.
 *
 * Until this succeeds the order is PENDING_PAYMENT: it does not count towards
 * revenue and no courier has been dispatched for it.
 */
export async function clientPayDelivery(id) {
  return apiFetch(`/client/deliveries/${id}/pay`, {
    method: 'PATCH',
  });
}

export async function clientCancelDelivery(id) {
  return apiFetch(`/client/deliveries/${id}/cancel`, {
    method: 'PATCH',
  });
}

export async function clientDeleteDelivery(id) {
  return apiFetch(`/client/deliveries/${id}`, {
    method: 'DELETE',
  });
}

export async function clientGetCourierLocation(id) {
  return apiFetch(`/client/deliveries/${id}/location`);
}

/**
 * The six-digit handover code the recipient shows at the door. Owner-only on
 * the server; refused for closed deliveries so a code is single-use.
 */
export async function clientGetHandoverCode(id) {
  return apiFetch(`/client/deliveries/${id}/handover-code`);
}

export async function clientRateDelivery(id, rating, reviewComment = '') {
  return apiFetch(`/client/deliveries/${id}/rate`, {
    method: 'POST',
    body: JSON.stringify({ rating, reviewComment }),
  });
}

// --- COURIER (LIVREUR) ---
export async function courierGetDeliveries() {
  return apiFetch('/courier/deliveries');
}

/**
 * The signed-in courier's own track record (deliveries completed, active,
 * cancelled, average rating). Takes no id on purpose: the backend scopes it to
 * the caller's token, so one courier cannot read another's numbers.
 */
export async function courierGetStats() {
  return apiFetch('/courier/deliveries/stats');
}

export async function courierUpdateStatus(id, status, extra = {}) {
  return apiFetch(`/courier/deliveries/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status, ...extra }),
  });
}

export async function courierSendLocation(id, lat, lng) {
  return apiFetch(`/courier/deliveries/${id}/location`, {
    method: 'PATCH',
    body: JSON.stringify({ latitude: lat, longitude: lng }),
  });
}

export async function courierOptimizeRoute(lat, lng) {
  return apiFetch(`/courier/deliveries/optimize?lat=${lat}&lng=${lng}`);
}

// ----------------------------------------------------
// Notifications API
// ----------------------------------------------------

export async function getNotifications() {
  return apiFetch('/notifications');
}

export function getNotificationStreamUrl() {
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;
  return `${API_BASE}/notifications/stream?token=${token || ''}`;
}

export async function getUnreadNotificationCount() {
  return apiFetch('/notifications/unread-count');
}

export async function markNotificationAsRead(id) {
  return apiFetch(`/notifications/${id}/read`, {
    method: 'PUT',
  });
}

export async function markAllNotificationsAsRead() {
  return apiFetch('/notifications/read-all', {
    method: 'PUT',
  });
}

// --- ADMIN ---
export async function adminGetDeliveries() {
  return apiFetch('/admin/deliveries');
}

export async function adminGetCouriers() {
  return apiFetch('/admin/users/couriers');
}

export async function adminAssignCourier(deliveryId, courierId) {
  return apiFetch(`/admin/deliveries/${deliveryId}/assign`, {
    method: 'PATCH',
    body: JSON.stringify({ courierId }),
  });
}

export async function adminGetCourierLocation(deliveryId) {
  return apiFetch(`/admin/deliveries/${deliveryId}/location`);
}

/**
 * Optimised route for a courier's current round.
 *
 * The path is nested under /admin/deliveries because that is where
 * AdminDeliveryController is mapped. It previously pointed at /admin/couriers/...
 * which matched no handler: the request 404'd, the generic handler turned it into
 * a 500, and AdminMap swallowed it — so the fleet map silently rendered courier
 * pins with no route polyline and no visible error.
 */
export async function adminGetCourierRoute(courierId, lat, lng) {
  return apiFetch(
    `/admin/deliveries/couriers/${courierId}/route?lat=${lat}&lng=${lng}`
  );
}

export async function adminDeleteDelivery(deliveryId) {
  return apiFetch(`/admin/deliveries/${deliveryId}`, {
    method: 'DELETE',
  });
}

export async function adminUpdateDeliveryStatus(deliveryId, status) {
  return apiFetch(`/admin/deliveries/${deliveryId}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  });
}

/**
 * Drops the courier from a delivery, returning it to PENDING so it can be
 * dispatched again. The mirror image of adminAssignCourier, and the only way to
 * put a wrongly-assigned order back in the pool.
 */
export async function adminUnassignCourier(deliveryId) {
  return apiFetch(`/admin/deliveries/${deliveryId}/assign`, { method: 'DELETE' });
}

export async function adminGetStats() {
  return apiFetch('/admin/deliveries/stats');
}

export async function adminGetAllUsers() {
  return apiFetch('/admin/users');
}

export async function adminGetActivities() {
  return apiFetch('/admin/deliveries/activities');
}

/**
 * One summary per delivery the trajectory model has scored, for the admin
 * dashboard's risk tag.
 *
 * @throws {ApiError} when the monitor is unreachable. That rejection is the
 *   point: the caller must be able to tell "scored, nothing suspicious" from
 *   "could not ask", and an empty array looks identical to both.
 */
export async function adminGetFraudTrails() {
  return apiFetch('/admin/deliveries/fraud-trails');
}

/**
 * The full per-fix score series for one delivery.
 *
 * Rejects with status 409 when the model has never scored it — a delivery that
 * was never driven has no trajectory, and inventing a flat line would be a
 * chart of nothing.
 */
export async function adminGetFraudTrail(deliveryId) {
  return apiFetch(`/admin/deliveries/${deliveryId}/fraud-trail`);
}

export { apiFetch, ApiError };
