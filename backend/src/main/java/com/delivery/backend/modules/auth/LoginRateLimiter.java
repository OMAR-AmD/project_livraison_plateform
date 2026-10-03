package com.delivery.backend.modules.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts failed sign-in attempts and refuses more once a threshold is crossed.
 *
 * <p>Two independent counters guard two different attacks, and both have to be
 * present:
 *
 * <ul>
 *   <li><b>per account</b> -- a targeted brute force against one mailbox. This is
 *   the control that has to work: it is keyed by the account and is the same
 *   whichever address the attacker calls from.</li>
 *   <li><b>per client</b> -- credential stuffing that tries one password against
 *   many accounts. Keyed by account alone it would never trip, because each
 *   account sees a single attempt. This counter is deliberately much looser,
 *   since a shared address (a home router, an office NAT, the reverse proxy in
 *   front of the app when no {@code X-Forwarded-For} is present) makes innocent
 *   users share one bucket.</li>
 * </ul>
 *
 * <p>Only failures are counted, and a successful sign-in clears the account
 * counter. Counting every attempt would let a legitimate user lock themselves
 * out by logging in normally, and the attacker this defends against is guessing
 * without valid credentials anyway.
 *
 * <p>The state is in memory, on purpose. Redis is already in the stack, but a
 * limiter that stops working when the cache is down is a security control with
 * an off switch, and this platform already runs one instance. The trade-off is
 * honest and worth stating: counters reset if the process restarts, and they are
 * per instance. Scaling to several instances would move this into Redis or
 * Postgres, and the policy here -- windows, thresholds, what counts -- is the
 * same decision that would have to be re-made then.
 */
@Component
public class LoginRateLimiter {

    /** Windows are swept this often (in increments) so the map cannot grow without bound. */
    private static final int SWEEP_EVERY = 1024;

    private final int maxPerAccount;
    private final int maxPerClient;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final AtomicLong writes = new AtomicLong();

    /**
     * The constructor Spring uses. Annotated because there is a second,
     * package-private constructor for tests: with more than one candidate,
     * Spring cannot pick one by itself, and the application refuses to start
     * with "No default constructor found" -- which is exactly what happened the
     * first time this shipped.
     */
    @Autowired
    public LoginRateLimiter(
            @Value("${application.security.login-rate-limit.max-per-account:5}") int maxPerAccount,
            @Value("${application.security.login-rate-limit.max-per-client:50}") int maxPerClient,
            @Value("${application.security.login-rate-limit.window-seconds:900}") long windowSeconds) {
        this(maxPerAccount, maxPerClient, Duration.ofSeconds(windowSeconds), Clock.systemUTC());
    }

    LoginRateLimiter(int maxPerAccount, int maxPerClient, Duration window, Clock clock) {
        this.maxPerAccount = maxPerAccount;
        this.maxPerClient = maxPerClient;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Refuses the attempt if either counter is already exhausted. Called before
     * the password is checked, so a blocked attacker costs no bcrypt work.
     *
     * @throws RateLimitExceededException when the caller must wait
     */
    public void checkAllowed(String clientId, String email) {
        long now = clock.millis();

        String account = accountKey(email);
        if (count(account, now) >= maxPerAccount) {
            throw new RateLimitExceededException(retryAfterSeconds(account, now));
        }

        if (clientId != null) {
            String client = clientKey(clientId);
            if (count(client, now) >= maxPerClient) {
                throw new RateLimitExceededException(retryAfterSeconds(client, now));
            }
        }
    }

    /** Records a failed sign-in against both the account and the client. */
    public void recordFailure(String clientId, String email) {
        long now = clock.millis();
        increment(accountKey(email), now);
        if (clientId != null) {
            increment(clientKey(clientId), now);
        }
        if (writes.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep(now);
        }
    }

    /** A correct password clears the account's failures; the client's are left to age out. */
    public void recordSuccess(String email) {
        failures.remove(accountKey(email));
    }

    private void increment(String key, long now) {
        failures.compute(key, (k, existing) -> {
            if (existing == null || expired(existing, now)) {
                return new Window(now, 1);
            }
            return new Window(existing.startMillis(), existing.count() + 1);
        });
    }

    private int count(String key, long now) {
        Window window = failures.get(key);
        if (window == null || expired(window, now)) {
            return 0;
        }
        return window.count();
    }

    private boolean expired(Window window, long now) {
        return now - window.startMillis() >= this.window.toMillis();
    }

    private long retryAfterSeconds(String key, long now) {
        Window window = failures.get(key);
        if (window == null) {
            return Math.max(1L, this.window.toSeconds());
        }
        long remainingMillis = window.startMillis() + this.window.toMillis() - now;
        return Math.max(1L, (remainingMillis + 999) / 1000);
    }

    private void sweep(long now) {
        failures.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    private static String accountKey(String email) {
        return "account:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String clientKey(String clientId) {
        return "client:" + clientId;
    }

    private record Window(long startMillis, int count) {}
}
