package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {

    private static final String PAN_WITH_KNOWN_BIN = "4000001234567890";
    private static final String PAN_WITH_OTHER_BIN = "5100001234567890";
    private static final String PAN_WITH_UNKNOWN_BIN = "9999991234567890";

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new RoutingService(properties());
    }

    @Test
    void shouldResolveIssuerIdWhenBinIsKnown() {
        // Act
        String issuerId = routingService.getIssuerIdByPan(PAN_WITH_KNOWN_BIN);

        // Assert
        assertThat(issuerId).isEqualTo("ISS001");
    }

    @Test
    void shouldResolveDifferentIssuerForAnotherBin() {
        // Act
        String issuerId = routingService.getIssuerIdByPan(PAN_WITH_OTHER_BIN);

        // Assert
        assertThat(issuerId).isEqualTo("ISS002");
    }

    @Test
    void shouldThrowWhenBinIsUnknown() {
        // Act / Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(PAN_WITH_UNKNOWN_BIN))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("999999");
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"", "40000", "12345"})
    void shouldThrowWhenPanIsShorterThanBinLength(String shortPan) {
        // Act / Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(shortPan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }

    @Test
    void shouldThrowWhenPanIsNull() {
        // Act / Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(null))
                .isInstanceOf(UnknownBinException.class);
    }

    private SwitchProperties properties() {
        return new SwitchProperties(
                "1.0.0",
                Map.of("400000", "ISS001", "510000", "ISS002"),
                "http://auth-service",
                "http://logger-service",
                "http://acquirer-service",
                new SwitchProperties.HttpProperties(1000, 2000, 2000),
                new SwitchProperties.RetryProperties(3, List.of(10L, 20L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 1000L)));
    }
}
