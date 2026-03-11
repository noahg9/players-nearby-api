package com.pickupsports.auth.service;

import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {

    // NOTE: This map grows unbounded — one entry per unique email seen since startup.
    // Acceptable for MVP at low user volume. At scale, replace with a Redis-backed Bucket4j ProxyManager.
    private final ConcurrentHashMap<String, Bucket> emailBuckets = new ConcurrentHashMap<>();

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
}
