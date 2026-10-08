package com.endpointposture.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    @Test
    void rateLimiterExhaustsTokensAndRefills() throws InterruptedException {
        // enabled=true, capacity=3, refillTokens=3, refillDurationSeconds=1
        LoginRateLimiter limiter = new LoginRateLimiter(true, 3, 3, 1);

        assertTrue(limiter.tryAcquire("192.168.1.10"));
        assertTrue(limiter.tryAcquire("192.168.1.10"));
        assertTrue(limiter.tryAcquire("192.168.1.10"));
        assertFalse(limiter.tryAcquire("192.168.1.10")); // exhausted

        // Different IP has its own bucket
        assertTrue(limiter.tryAcquire("192.168.1.20"));

        // Wait for refill of at least 1 token (3 tokens / 1 sec = 1 token per 333 ms)
        Thread.sleep(400);
        assertTrue(limiter.tryAcquire("192.168.1.10"));
    }

    @Test
    void disabledRateLimiterAlwaysAllows() {
        LoginRateLimiter limiter = new LoginRateLimiter(false, 1, 1, 60);
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
    }
}
