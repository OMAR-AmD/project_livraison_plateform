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
        super("Too many failed sign-in attempts. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
