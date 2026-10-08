package com.endpointposture.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-bucket rate limiter per client IP address.
 *
 * <p>Protects {@code POST /api/v1/auth/login} against credential stuffing and brute force
 * flood attacks while keeping account lockout separate and intact.</p>
 */
@Component
public class LoginRateLimiter {

    private final boolean enabled;
    private final int capacity;
    private final double refillRatePerNano;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${app.security.login.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.security.login.rate-limit.capacity:10}") int capacity,
            @Value("${app.security.login.rate-limit.refill-tokens:10}") int refillTokens,
            @Value("${app.security.login.rate-limit.refill-duration-seconds:60}") long refillDurationSeconds) {
        this.enabled = enabled;
        this.capacity = capacity;
        long durationNanos = Math.max(1, refillDurationSeconds) * 1_000_000_000L;
        this.refillRatePerNano = (double) refillTokens / durationNanos;
    }

    /**
     * Attempts to consume 1 token for the specified client IP.
     *
     * @param clientIp remote client IP address
     * @return {@code true} if allowed (token consumed or limiter disabled); {@code false} if exhausted
     */
    public boolean tryAcquire(String clientIp) {
        if (!enabled) {
            return true;
        }
        String ip = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
        TokenBucket bucket = buckets.computeIfAbsent(ip, k -> new TokenBucket(capacity, System.nanoTime()));
        return bucket.tryConsume(capacity, refillRatePerNano);
    }

    /** Clears all in-memory buckets (useful for test resets). */
    public void reset() {
        buckets.clear();
    }

    private static class TokenBucket {
        private double tokens;
        private long lastRefillNanos;

        public TokenBucket(double capacity, long nowNanos) {
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }

        public synchronized boolean tryConsume(int capacity, double refillRatePerNano) {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + (elapsed * refillRatePerNano));
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
