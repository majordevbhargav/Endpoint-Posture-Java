package com.endpointposture.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtAuthFilter delegates every decision to JwtService, so its correctness
 * matters independently of the filter. Covers issuance, round-trip claim
 * extraction, the startup key-length guard, and the failure modes the
 * filter relies on isValid() to catch (bad signature, expiry, garbage input).
 */
class JwtServiceTest {

    private static final String SECRET = "test-only-secret-key-at-least-256-bits-long-for-hmac-sha";

    @Test
    void tokenRoundTripsUsernameAndRole() {
        JwtService service = new JwtService(SECRET, 60);
        String token = service.generateToken("alice", "ADMIN");

        assertTrue(service.isValid(token));
        assertEquals("alice", service.extractUsername(token));
        assertEquals("ADMIN", service.extractRole(token));
    }

    @Test
    void tooShortSecretFailsFastAtConstruction() {
        assertThrows(Exception.class, () -> new JwtService("too-short", 60));
    }

    @Test
    void expiredTokenIsInvalid() {
        JwtService expired = new JwtService(SECRET, -1); // already expired on issue
        String token = expired.generateToken("bob", "ADMIN");
        assertFalse(expired.isValid(token));
    }

    @Test
    void tokenFromADifferentSecretIsInvalid() {
        JwtService issuer = new JwtService(SECRET, 60);
        JwtService verifier = new JwtService("a-completely-different-256-bit-secret-value-here", 60);
        String token = issuer.generateToken("mallory", "ADMIN");
        assertFalse(verifier.isValid(token));
    }

    @Test
    void garbageStringIsInvalid() {
        JwtService service = new JwtService(SECRET, 60);
        assertFalse(service.isValid("not.a.jwt"));
    }

    @Test
    void differentUsersGetIndependentTokens() {
        JwtService service = new JwtService(SECRET, 60);
        String a = service.generateToken("alice", "ADMIN");
        String b = service.generateToken("bob", "ADMIN");
        assertNotEquals(a, b);
        assertEquals("alice", service.extractUsername(a));
        assertEquals("bob", service.extractUsername(b));
    }
}