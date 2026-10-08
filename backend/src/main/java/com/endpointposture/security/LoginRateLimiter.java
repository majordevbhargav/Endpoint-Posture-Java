package com.endpointposture.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory token-bucket rate limiter per client IP address.
 *
 * <p>Protects {@code POST /api/v1/auth/login} against credential stuffing and brute force
 * flood attacks while keeping account lockout separate and intact.</p>
 *
 * <p>The bucket map is bounded in practice: every {@value #SWEEP_EVERY_CALLS} calls, buckets
 * that have been idle for longer than ten minutes (or twice the time a bucket needs to refill
 * completely, whichever is longer) are dropped. Dropping an idle bucket is safe because a
 * fresh bucket starts full, which is exactly what an idle one would have refilled to.</p>
 */
@Component
public class LoginRateLimiter {

    private static final long MIN_IDLE_NANOS = 10L * 60L * 1_000_000_000L;
    private static final int SWEEP_EVERY_CALLS = 1000;

    private final boolean enabled;
    private final int capacity;
    private final double refillRatePerNano;
    private final long idleEvictNanos;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public LoginRateLimiter(
            @Value("${app.security.login.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.security.login.rate-limit.capacity:10}") int capacity,
            @Value("${app.security.login.rate-limit.refill-tokens:10}") int refillTokens,
            @Value("${app.security.login.rate-limit.refill-duration-seconds:60}") long refillDurationSeconds) {
        this.enabled = enabled;
        this.capacity = capacity;
        long durationNanos = Math.max(1, refillDurationSeconds) * 1_000_000_000L;
        this.refillRatePerNano = (double) refillTokens / durationNanos;
        if (refillRatePerNano > 0) {
            long fullRefillNanos = (long) (capacity / refillRatePerNano);
            this.idleEvictNanos = Math.max(MIN_IDLE_NANOS, 2L * fullRefillNanos);
        } else {
            this.idleEvictNanos = Long.MAX_VALUE; // a bucket that never refills must never be forgotten
        }
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
        boolean allowed = bucket.tryConsume(capacity, refillRatePerNano);

        if (calls.incrementAndGet() % SWEEP_EVERY_CALLS == 0) {
            evictIdle(System.nanoTime());
        }
        return allowed;
    }

    /** Clears all in-memory buckets (useful for test resets). */
    public void reset() {
        buckets.clear();
    }

    /** @return how many client buckets are currently held (for tests and diagnostics) */
    int bucketCount() {
        return buckets.size();
    }

    /** Drops buckets not touched for longer than the idle limit, measured against {@code nowNanos}. */
    void evictIdle(long nowNanos) {
        buckets.entrySet().removeIf(e -> nowNanos - e.getValue().lastAccessNanos() > idleEvictNanos);
    }

    private static class TokenBucket {
        private double tokens;
        private long lastRefillNanos;
        private volatile long lastAccessNanos;

        TokenBucket(double capacity, long nowNanos) {
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
            this.lastAccessNanos = nowNanos;
        }

        long lastAccessNanos() {
            return lastAccessNanos;
        }

        synchronized boolean tryConsume(int capacity, double refillRatePerNano) {
            long now = System.nanoTime();
            lastAccessNanos = now;
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