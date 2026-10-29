package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCircuitBreakerTest {

    private static final String SERVICE = "authorization";
    private static final Duration OPEN_DURATION = Duration.ofSeconds(10);
    private static final int FAILURE_THRESHOLD = 3;

    private MutableClock clock;
    private InMemoryCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-29T10:00:00Z"));
        circuitBreaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, FAILURE_THRESHOLD, clock);
    }

    @Test
    void shouldAllowRequestsWhileCircuitIsClosed() {
        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldOpenCircuitAfterThresholdFailures() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldNotOpenCircuitBeforeThresholdIsReached() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD - 1);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldAllowSingleProbeAfterOpenDuration() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);
        clock.advance(OPEN_DURATION.plusSeconds(1));

        // Act
        boolean probe = circuitBreaker.allowRequest(SERVICE);
        boolean concurrentRequest = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(probe).isTrue();
        assertThat(concurrentRequest).isFalse();
    }

    @Test
    void shouldCloseCircuitWhenProbeSucceeds() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);
        clock.advance(OPEN_DURATION.plusSeconds(1));
        circuitBreaker.allowRequest(SERVICE);

        // Act
        circuitBreaker.recordSuccess(SERVICE);
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldOpenCircuitAgainWhenProbeFails() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);
        clock.advance(OPEN_DURATION.plusSeconds(1));
        circuitBreaker.allowRequest(SERVICE);

        // Act
        circuitBreaker.recordFailure(SERVICE);
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepIndependentStatePerService() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);

        // Act
        boolean otherServiceAllowed = circuitBreaker.allowRequest("card-management");

        // Assert
        assertThat(otherServiceAllowed).isTrue();
    }

    private void recordFailures(int count) {
        for (int i = 0; i < count; i++) {
            circuitBreaker.recordFailure(SERVICE);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
