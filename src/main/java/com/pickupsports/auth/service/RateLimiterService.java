package com.pickupsports.auth.service;

import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {

    // NOTE: These maps grow unbounded — one entry per unique key seen since startup.
    // Acceptable for MVP at low user volume. At scale, replace with a Redis-backed Bucket4j ProxyManager.
    private final ConcurrentHashMap<String, Bucket> emailBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> guestJoinBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> chatBuckets = new ConcurrentHashMap<>();

    /**
     * Returns true if the request is allowed; false if the rate limit is exceeded.
     * Limit: 5 magic link requests per email address per hour.
     */
    public boolean tryConsumeRequestLogin(String email) {
        Bucket bucket = emailBuckets.computeIfAbsent(
            email.toLowerCase(),
            k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(5).refillGreedy(5, Duration.ofHours(1)))
                .build()
        );
        return bucket.tryConsume(1);
    }

    /**
     * Returns true if the request is allowed; false if the rate limit is exceeded.
     * Limit: 20 guest join attempts per IP per hour.
     */
    public boolean tryConsumeGuestJoin(String ip) {
        Bucket bucket = guestJoinBuckets.computeIfAbsent(
            ip,
            k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(20).refillGreedy(20, Duration.ofHours(1)))
                .build()
        );
        return bucket.tryConsume(1);
    }

    /**
     * Returns true if the request is allowed; false if rate limit exceeded.
     * Limit: 20 messages per key per minute.
     * Key = userId.toString() for auth users, guestToken for guests.
     */
    public boolean tryConsumeChat(String key) {
        Bucket bucket = chatBuckets.computeIfAbsent(
            key,
            k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(20).refillGreedy(20, Duration.ofMinutes(1)))
                .build()
        );
        return bucket.tryConsume(1);
    }
}
