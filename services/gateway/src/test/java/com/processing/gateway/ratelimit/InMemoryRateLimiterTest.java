package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private static final String KEY = "POST /api/transactions:127.0.0.1";
    private static final int CAPACITY = 2;
    private static final double REFILL_PER_SECOND = 1.0;
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);
    private static final long MAX_BUCKETS = 100;

    private MutableTicker ticker;
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        ticker = new MutableTicker();
        rateLimiter = InMemoryRateLimiter.forTesting(CAPACITY, REFILL_PER_SECOND, BUCKET_TTL, MAX_BUCKETS, ticker);
    }

    @Test
    void shouldAllowRequestsWhileTokensAreAvailable() {
        // Act
        boolean first = rateLimiter.allowRequest(KEY);
        boolean second = rateLimiter.allowRequest(KEY);

        // Assert
        assertThat(first).isTrue();
        assertThat(second).isTrue();
    }

    @Test
    void shouldRejectRequestWhenBucketIsExhausted() {
        // Arrange
        rateLimiter.allowRequest(KEY);
        rateLimiter.allowRequest(KEY);

        // Act
        boolean third = rateLimiter.allowRequest(KEY);

        // Assert
        assertThat(third).isFalse();
    }

    @Test
    void shouldRefillTokensAfterTimePasses() {
        // Arrange
        rateLimiter.allowRequest(KEY);
        rateLimiter.allowRequest(KEY);

        // Act
        ticker.advance(Duration.ofSeconds(1));
        boolean allowedAfterRefill = rateLimiter.allowRequest(KEY);

        // Assert
        assertThat(allowedAfterRefill).isTrue();
    }

    @Test
    void shouldKeepSeparateBucketsForDifferentKeys() {
        // Arrange
        rateLimiter.allowRequest(KEY);
        rateLimiter.allowRequest(KEY);

        // Act
        boolean otherKeyAllowed = rateLimiter.allowRequest("POST /api/transactions:10.0.0.1");

        // Assert
        assertThat(otherKeyAllowed).isTrue();
    }

    @Test
    void shouldRejectAllRequestsWhenCapacityIsZero() {
        // Arrange
        InMemoryRateLimiter zeroCapacityLimiter = InMemoryRateLimiter.forTesting(
                0, REFILL_PER_SECOND, BUCKET_TTL, MAX_BUCKETS, ticker);

        // Act
        boolean allowed = zeroCapacityLimiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldAllowCleanUpWithoutFailing() {
        // Arrange
        rateLimiter.allowRequest(KEY);

        // Act
        ticker.advance(BUCKET_TTL.plusMinutes(1));
        rateLimiter.cleanUp();

        // Assert
        assertThat(rateLimiter.allowRequest(KEY)).isTrue();
    }

    private static final class MutableTicker implements Ticker {

        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }
}
