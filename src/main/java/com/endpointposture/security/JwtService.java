package com.endpointposture.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Creates and verifies the JWTs used to authenticate dashboard/API callers.
 *
 * <p>Tokens are signed with HMAC-SHA256. The signing secret and token
 * lifetime come from {@code app.jwt.secret} and
 * {@code app.jwt.expiration-minutes} in {@code application.yml}.</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    /**
     * @param secret            signing secret; must be at least 32 bytes (256 bits)
     *                          or key creation fails at startup
     * @param expirationMinutes how long an issued token stays valid
     */
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-minutes}") long expirationMinutes
    ) {
        // The secret's raw bytes become the HMAC key. JJWT rejects anything
        // shorter than 256 bits (WeakKeyException), so a too-short secret
        // fails fast at startup rather than producing weak tokens. The dev
        // default in application.yml is intentionally obviously fake -
        // replace it via the JWT_SECRET env var before this is ever exposed
        // beyond your own laptop.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    /**
     * Issues a signed token for a user.
     *
     * @param username becomes the token's subject
     * @param role     stored in a {@code role} claim, read back by {@link JwtAuthFilter}
     * @return the compact JWT string
     */
    public String generateToken(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * @param token a valid JWT
     * @return the username stored as the token's subject
     */
    public String extractUsername(String token) {
        return parse(token).getPayload().getSubject();
    }

    /**
     * @param token a valid JWT
     * @return the value of the {@code role} claim
     */
    public String extractRole(String token) {
        return parse(token).getPayload().get("role", String.class);
    }

    /**
     * Checks signature and expiry.
     *
     * @param token the token to check
     * @return {@code true} if the token is well-formed, correctly signed and not expired
     */
    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private io.jsonwebtoken.Jws<io.jsonwebtoken.Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }
}
