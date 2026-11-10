package com.eyelesscode.task_by_task.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    public static final int DEFAULT_CAPACITY = 5;
    public static final Duration DEFAULT_REFILL_DURATION = Duration.ofMinutes(1);
    public static final long DEFAULT_MAX_CACHE_SIZE = 10_000L;
    public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    private final int capacity;
    private final Duration refillDuration;
    private final Cache<String, Bucket> cache;

    public RateLimitService() {
        this(
                DEFAULT_CAPACITY,
                DEFAULT_REFILL_DURATION,
                DEFAULT_MAX_CACHE_SIZE,
                DEFAULT_EXPIRE_AFTER_ACCESS);
    }

    public RateLimitService(
            int capacity, Duration refillDuration, long maxCacheSize, Duration expireAfterAccess) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.refillDuration =
                Objects.requireNonNull(refillDuration, "refillDuration must not be null");
        this.cache =
                Caffeine.newBuilder()
                        .maximumSize(maxCacheSize)
                        .expireAfterAccess(expireAfterAccess)
                        .build();
    }

    private Bucket createBucket() {
        Bandwidth limit =
                Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, refillDuration)
                        .build();
        return Bucket.builder().addLimit(limit).build();
    }

    public void consumeOrThrow(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Bucket bucket = cache.get(key, k -> createBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long nanosToWait = probe.getNanosToWaitForRefill();
            long retryAfterSeconds = Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
            throw new RateLimitException(retryAfterSeconds);
        }
    }

    public boolean tryConsume(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Bucket bucket = cache.get(key, k -> createBucket());
        return bucket.tryConsume(1);
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "UNKNOWN";
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : "UNKNOWN";
    }

    public String buildPartitionKey(HttpServletRequest request, String action) {
        return resolveClientIp(request) + ":" + (action != null ? action.trim() : "default");
    }

    public void clear() {
        cache.invalidateAll();
    }
}
