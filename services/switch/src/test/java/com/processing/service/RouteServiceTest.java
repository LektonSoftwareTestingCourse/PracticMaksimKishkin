package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "123456789012";
    private static final String AUTH_CODE = "ABC123";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private Instant requestTime;

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        requestTime = Instant.now();
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldReturnApprovedResponseAndPublishTransaction() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("1.50"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(captor.getValue().issuerId()).isEqualTo(ISSUER_ID);
        assertThat(captor.getValue().acquiringFee()).isEqualByComparingTo("1.50");
    }

    @Test
    void shouldReturnDeclinedWhenBinIsUnknown() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void shouldReturnDeclinedWhenAuthorizationIsUnavailable() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any()))
                .thenThrow(new AuthorizationException(STAN, 3, "connection refused"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
    }

    @Test
    void shouldRollbackAndReturnSystemErrorWhenPublishFailsForApprovedTransaction() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ONE);
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN))).thenReturn(approvedRollback());

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        verify(authorizationClient).rollback(any(), eq(RRN));
    }

    @Test
    void shouldNotRollbackWhenPublishFailsForDeclinedTransaction() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declinedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ONE);
        when(loggerClient.log(any())).thenReturn(false);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("CARD_BLOCKED");
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldHandleShortPanAsUnknownBin() {
        // Arrange
        when(routingService.getIssuerIdByPan("123")).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request("123"));

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
    }

    @Test
    void shouldHandleNullPanAsUnknownBin() {
        // Arrange
        when(routingService.getIssuerIdByPan(isNull())).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request(null));

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
    }

    @Test
    void shouldStoreZeroProcessingTimeWhenResponseHasNoTiming() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(new AuthorizationResponse(
                "0100", STAN, RRN, AUTH_CODE, AuthorizationResponse.CODE_APPROVED,
                AuthorizationResponse.STATUS_APPROVED, null, null));
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(null);
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        routeService.route(request(PAN));

        // Assert
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(captor.capture());
        assertThat(captor.getValue().processingTimeMs()).isZero();
        assertThat(captor.getValue().acquiringFee()).isNull();
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest(
                "0100", STAN, pan, "000000", AMOUNT, "643", requestTime,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", null);
    }

    private AuthorizationResponse approvedResponse() {
        return AuthorizationResponse.approved(request(PAN), RRN, AUTH_CODE, requestTime);
    }

    private AuthorizationResponse declinedResponse() {
        return AuthorizationResponse.declined(request(PAN), "CARD_BLOCKED",
                AuthorizationResponse.CODE_DECLINED_GENERAL, requestTime);
    }

    private RollbackResponse approvedRollback() {
        return RollbackResponse.approved(new RollbackRequest(RRN, PAN, AMOUNT), requestTime);
    }
}
