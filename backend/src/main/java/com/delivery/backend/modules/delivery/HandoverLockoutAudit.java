package com.delivery.backend.modules.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The lockout audit trail for the handover-code throttle.
 *
 * <p>Two things are worth a line an operator can find later, and they are
 * different events. A <em>lockout</em> is a delivery or a courier that reached
 * the ceiling and was refused -- the throttle working as intended, but worth
 * seeing, because a cluster of them is what an attack looks like. A
 * <em>fail-closed</em> is the counter store being unreachable, so the throttle
 * refused for that reason instead; that one is the alarming case, because it
 * means legitimate doorstep codes are being turned away while Redis is down, so
 * it logs at error and names the operation.
 *
 * <p>Kept as its own component rather than an inline logger so the limiter's
 * tests can assert that the audit actually fires; a log line no test ever sees is
 * a log line that quietly stops being written.
 *
 * <p>Today this writes to the application log under a dedicated logger name,
 * {@code HANDOVER_LOCKOUT_AUDIT}, so it can be routed to its own sink without
 * scraping the delivery logs. A queryable store -- a capped Redis list, a table
 * -- is the next step if the trail has to be searched rather than grepped.
 */
@Component
public class HandoverLockoutAudit {

    private static final Logger log = LoggerFactory.getLogger("HANDOVER_LOCKOUT_AUDIT");

    /** A guesser hit the ceiling and was refused. */
    public void lockedOut(String scope, String subject, int count, int limit, long retryAfterSeconds) {
        log.warn("handover-code lockout scope={} subject={} count={} limit={} retry-after={}s",
                scope, subject, count, limit, retryAfterSeconds);
    }

    /** The counter store could not be read, so the attempt was refused to fail closed. */
    public void failClosed(String operation, String subject, Throwable cause) {
        log.error("handover-code throttle FAIL-CLOSED: could not {} for {}; refusing the attempt "
                + "until the store is reachable ({})", operation, subject, cause.toString());
    }
}
