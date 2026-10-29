package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SuppressWarnings("checkstyle:MultipleStringLiterals")
class AuthorizationClientTest {

    private static final String AUTHORIZATION_URL = "http://auth-service";
    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String RRN = "123456789012";
    private static final Instant REQUEST_TIME = Instant.parse("2026-10-29T10:00:00Z");

    private MockRestServiceServer server;
    private AuthorizationClient authorizationClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        authorizationClient = new AuthorizationClient(
                properties(),
                builder.build(),
                Retry.of("authorizationRetry", RetryConfig.custom()
                        .maxAttempts(1)
                        .waitDuration(Duration.ofMillis(1))
                        .build()),
                CircuitBreaker.ofDefaults("authorizationCircuitBreaker"));
    }

    @Test
    void shouldReturnApprovedResponseWhenAuthorizationAcceptsRequest() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/api/internal/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(approvedBody(), MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
        server.verify();
    }

    @Test
    void shouldReturnDeclinedResponseWhenAuthorizationDeclinesRequest() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/api/internal/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(declinedBody(), MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("CARD_BLOCKED");
    }

    @Test
    void shouldFailWhenAuthorizationReturnsUnknownStatus() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/api/internal/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"mti":"0100","stan":"000001","status":"ERROR","responseCode":"99"}
                        """, MediaType.APPLICATION_JSON));

        // Act / Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request()))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldFailWhenAuthorizationIsUnavailable() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/api/internal/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act / Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request()))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldReturnRollbackResponseWhenRollbackIsAccepted() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/api/internal/rollback"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"rrn":"123456789012","responseCode":"00","status":"APPROVED","processingTimeMs":1}
                        """, MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        assertThat(response.rrn()).isEqualTo(RRN);
    }

    @Test
    void shouldReturnNullWhenRollbackFails() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/api/internal/rollback"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReportOkWhenHealthEndpointResponds() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        // Act
        String health = authorizationClient.checkHealth();

        // Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    void shouldReportDownWhenHealthEndpointFails() {
        // Arrange
        server.expect(requestTo(AUTHORIZATION_URL + "/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Act
        String health = authorizationClient.checkHealth();

        // Assert
        assertThat(health).isEqualTo("down");
    }

    private String approvedBody() {
        return """
                {"mti":"0100","stan":"000001","rrn":"123456789012","authCode":"ABC123",
                 "responseCode":"00","status":"APPROVED","processingTimeMs":5}
                """;
    }

    private String declinedBody() {
        return """
                {"mti":"0100","stan":"000001","responseCode":"05","status":"DECLINED",
                 "declineReason":"CARD_BLOCKED","processingTimeMs":3}
                """;
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000", new BigDecimal("1000"), "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }

    private SwitchProperties properties() {
        return new SwitchProperties(
                "1.0.0",
                Map.of("400000", "ISS001"),
                AUTHORIZATION_URL,
                "http://logger-service",
                "http://acquirer-service",
                new SwitchProperties.HttpProperties(1000, 2000, 2000),
                new SwitchProperties.RetryProperties(1, List.of(1L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 1000L)));
    }
}
