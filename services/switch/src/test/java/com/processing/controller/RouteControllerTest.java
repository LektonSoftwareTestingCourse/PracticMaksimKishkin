package com.processing.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
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
    private Logger routeControllerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        routeController = new RouteController(routeService);
    }

    @AfterEach
    void tearDown() {
        if (logAppender != null) {
            routeControllerLogger.detachAppender(logAppender);
            logAppender.stop();
        }
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
    void shouldPassThroughResponseWhenPanCannotBeMasked(String pan) {
        // Arrange
        AuthorizationResponse expected = AuthorizationResponse.unknownBin("000001");
        when(routeService.route(any())).thenReturn(expected);

        // Act
        ResponseEntity<AuthorizationResponse> response = routeController.routeTransaction(request(pan));

        // Assert
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void shouldMaskPanInLogs() {
        // Arrange
        routeControllerLogger = (Logger) LoggerFactory.getLogger(RouteController.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        routeControllerLogger.addAppender(logAppender);
        AuthorizationResponse expected = AuthorizationResponse.approved(request(PAN), "123456789012", "ABC123",
                Instant.now());
        when(routeService.route(any())).thenReturn(expected);

        // Act
        routeController.routeTransaction(request(PAN));

        // Assert
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("4000****7890") && !message.contains(PAN));
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000", new BigDecimal("1000"), "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }
}
