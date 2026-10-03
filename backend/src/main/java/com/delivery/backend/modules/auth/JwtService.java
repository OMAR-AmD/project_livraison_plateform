package com.delivery.backend.modules.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    /** HS256 needs a key of at least 256 bits. */
    public static final int MIN_SECRET_BYTES = 32;

    /**
     * Claim holding the account's {@code tokenVersion} at mint time. Short
     * because it rides in every token and is read on every request.
     */
    public static final String TOKEN_VERSION_CLAIM = "tv";

    // Injects the values straight from your application.yml
    @Value("${application.security.jwt.secret-key}")
    private String secretKey;

    @Value("${application.security.jwt.expiration}")
    private long jwtExpiration;

    /**
     * Extracts the primary identifier (Email) from the token's payload.
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Generic method to pull any specific piece of data (claim) out of the token.
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Generates a standard token with no extra data, just the user's details.
     *
     * <p>When the caller is one of our own accounts, the token also records the
     * account's current {@code tokenVersion}, so the token can later be revoked
     * by incrementing that counter. A generic {@link UserDetails} that is not our
     * entity simply gets no such claim.
     */
    public String generateToken(UserDetails userDetails) {
        Map<String, Object> extraClaims = new HashMap<>();
        if (userDetails instanceof User user) {
            extraClaims.put(TOKEN_VERSION_CLAIM, user.getTokenVersion());
        }
        return generateToken(extraClaims, userDetails);
    }

    /**
     * The version this token was minted with, or {@code null} for a token that
     * predates versioning (or was minted for a non-{@link User} principal).
     */
    public Long extractTokenVersion(String token) {
        Object value = extractClaim(token, claims -> claims.get(TOKEN_VERSION_CLAIM));
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    /**
     * The core generator. Assembles the Header, Payload (Claims), and signs it 
     * mathematically using the HMAC-SHA256 algorithm.
     */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignInKey(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validates that the token belongs to the user making the request, hasn't
     * expired, and was issued for the account's current session generation.
     *
     * <p>The version check is the only server-side lever over an otherwise
     * stateless token. A token minted before the account's last logout carries a
     * stale {@code tv} and is refused here even though its signature and expiry
     * are perfectly good. A token with no {@code tv} at all is read as version
     * zero, which is what every account starts at, so tokens issued before this
     * mechanism existed survive until that account's first logout.
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        if (!username.equals(userDetails.getUsername()) || isTokenExpired(token)) {
            return false;
        }
        if (userDetails instanceof User user) {
            Long issuedVersion = extractTokenVersion(token);
            long version = issuedVersion == null ? 0L : issuedVersion;
            return version == user.getTokenVersion();
        }
        return true;
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Parses the token. If the token is modified or signed with a different key, 
     * this method will throw a cryptographic exception.
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * The configured secret, used as raw bytes.
     *
     * <p>This used to be {@code Decoders.BASE64.decode(secretKey)}, and that was a
     * trap rather than a decision. The documented way to produce the key is
     * {@code openssl rand -hex 32}, which yields 64 hex characters -- and hex
     * characters are all legal Base64 characters, in a length that is a multiple
     * of 4, so it decoded to 48 bytes and everything worked. That is why nobody
     * noticed: the one command the project tells you to run happened to survive
     * the wrong decoder.
     *
     * <p>Any other value did not survive it. A 49-character placeholder could not
     * be Base64-decoded, JJWT threw, and the generic {@code JwtException} handler
     * answered <em>login</em> with 401 "Authentication failed" -- indistinguishable
     * from a wrong password, and pointing at the user's credentials rather than
     * at the deployment's configuration.
     *
     * <p>Raw UTF-8 bytes make the contract the one that is actually documented:
     * a string of at least {@value #MIN_SECRET_BYTES} bytes, however you chose to
     * generate it. {@link #checkSecretKey()} refuses anything shorter at startup,
     * so a weak key is a boot failure naming the variable rather than a mystery
     * 401 that a user is asked to debug.
     */
    private SecretKey getSignInKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Fails the boot on an unusable signing key.
     *
     * <p>Without this, a key that is present but too short is accepted at
     * startup and only explodes when someone tries to log in -- as a 401 that
     * looks like a credential problem. The same reasoning as
     * TrajectoryFraudConfig: a platform that cannot authenticate correctly
     * should refuse to start rather than run and mislead.
     */
    @PostConstruct
    void checkSecretKey() {
        int bytes = secretKey == null ? 0 : secretKey.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET_KEY is " + bytes + " byte(s); HS256 needs at least " + MIN_SECRET_BYTES
                            + ". Generate one with: openssl rand -hex 32");
        }
    }
}