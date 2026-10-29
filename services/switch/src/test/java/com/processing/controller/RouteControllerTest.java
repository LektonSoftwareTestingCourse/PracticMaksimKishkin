package com.processing.controller;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    private static final String PAN = "4000001234567890";
    private static final Instant REQUEST_TIME = Instant.parse("2026-10-29T10:00:00Z");

    @Mock
    private RouteService routeService;

    private RouteController routeController;

    @BeforeEach
    void setUp() {
        routeController = new RouteController(routeService);
    }

    @Test
    void shouldReturnOkWithResponseFromRouteService() {
        // Arrange
        AuthorizationResponse expected = AuthorizationResponse.approved(request(PAN), "123456789012", "ABC123",
                REQUEST_TIME);
        when(routeService.route(any())).thenReturn(expected);

        // Act
        ResponseEntity<AuthorizationResponse> response = routeController.routeTransaction(request(PAN));

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @ParameterizedTest(name = "pan={0}")
    @NullSource
    @ValueSource(strings = {"", "123", "4000"})
    void shouldHandlePanThatCannotBeMasked(String pan) {
        // Arrange
        AuthorizationResponse expected = AuthorizationResponse.unknownBin("000001");
        when(routeService.route(any())).thenReturn(expected);

        // Act
        ResponseEntity<AuthorizationResponse> response = routeController.routeTransaction(request(pan));

        // Assert
        assertThat(response.getBody()).isSameAs(expected);
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000", new BigDecimal("1000"), "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }
}
