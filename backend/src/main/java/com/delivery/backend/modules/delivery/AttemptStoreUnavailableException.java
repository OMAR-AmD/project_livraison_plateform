package com.delivery.backend.modules.delivery;

/**
 * Raised when the shared handover-counter store cannot be reached or answered.
 *
 * <p>Kept separate from the Spring Data Redis exception types on purpose: the
 * limiter has to be able to fail closed without compiling against Redis, and a
 * different store -- a database, a service -- would raise the same signal.
 */
public class AttemptStoreUnavailableException extends RuntimeException {

    public AttemptStoreUnavailableException(String message) {
        super(message);
    }

    public AttemptStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
