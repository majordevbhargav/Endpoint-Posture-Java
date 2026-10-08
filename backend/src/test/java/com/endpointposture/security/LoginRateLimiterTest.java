package com.endpointposture.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void idleBucketsAreEvictedButRecentOnesAreKept() {
        LoginRateLimiter limiter = new LoginRateLimiter(true, 3, 3, 60);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        assertEquals(2, limiter.bucketCount());

        // 5 minutes later: under the 10 minute floor, nothing is dropped.
        limiter.evictIdle(System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
        assertEquals(2, limiter.bucketCount());

        // 11 minutes later: both are idle and dropped.
        limiter.evictIdle(System.nanoTime() + TimeUnit.MINUTES.toNanos(11));
        assertEquals(0, limiter.bucketCount());
    }

    @Test
    void anEvictedClientStartsAgainWithAFullBucket() {
        LoginRateLimiter limiter = new LoginRateLimiter(true, 2, 2, 3600);
        assertTrue(limiter.tryAcquire("x"));
        assertTrue(limiter.tryAcquire("x"));
        assertFalse(limiter.tryAcquire("x")); // exhausted, refills only slowly

        // Idle limit here is 2 x full refill time (2 hours), so go well beyond it.
        limiter.evictIdle(System.nanoTime() + TimeUnit.HOURS.toNanos(3));
        assertEquals(0, limiter.bucketCount());
        assertTrue(limiter.tryAcquire("x"));
    }

    @Test
    void aStillExhaustedBucketSurvivesASweepThatIsTooEarly() {
        LoginRateLimiter limiter = new LoginRateLimiter(true, 1, 1, 3600);
        assertTrue(limiter.tryAcquire("y"));
        assertFalse(limiter.tryAcquire("y"));

        limiter.evictIdle(System.nanoTime() + TimeUnit.MINUTES.toNanos(30));
        assertEquals(1, limiter.bucketCount());
        assertFalse(limiter.tryAcquire("y"));
    }
}