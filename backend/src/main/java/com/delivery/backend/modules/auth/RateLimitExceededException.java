package com.delivery.backend.modules.auth;

/**
 * Thrown when a caller has failed to sign in too many times, so the request is
 * refused before the password is even checked.
 *
 * <p>Carries the wait in seconds so the HTTP layer can answer with a
 * {@code Retry-After} header. A 429 without it tells a well-behaved client to
 * back off but not how long for, which invites a retry storm.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        this("Too many failed sign-in attempts. Try again in " + retryAfterSeconds + " seconds.",
                retryAfterSeconds);
    }

    /**
     * The same refusal, with a message naming the action that was throttled. The
     * sign-in limiter was the first caller, but the handover-code limiter reuses
     * this type so the HTTP layer keeps one 429-with-{@code Retry-After} path
     * instead of two that can drift apart. A generic "too many sign-in attempts"
     * on a delivery endpoint would describe an attack the user never made.
     */
    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
