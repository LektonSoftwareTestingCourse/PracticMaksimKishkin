package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String STAN = "000001";
    private static final String RRN = "123456789012";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal BALANCE = new BigDecimal("20000");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("8000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("25000");
    private static final UUID CARD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private Instant requestTime;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        requestTime = Instant.now();
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }

    @Test
    void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
        // Arrange
        givenCard(cardWithStatus(CardModelStatus.ACTIVE));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(100L);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
        assertThat(response.rrn()).hasSize(12);
        assertThat(response.authCode()).hasSize(6);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldDeclineWhenCardStatusIsNotActive(CardModelStatus status) {
        // Arrange
        givenCard(cardWithStatus(status));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isNotBlank();
    }

    @ParameterizedTest(name = "status={0} => {1}/{2}")
    @CsvSource({
            "BLOCKED,  CARD_BLOCKED,  05",
            "INACTIVE, CARD_INACTIVE, 05",
            "EXPIRED,  CARD_EXPIRED,  54"
    })
    void shouldMapCardStatusToDeclineOutcome(CardModelStatus status, String reason, String code) {
        // Arrange
        givenCard(cardWithStatus(status));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
    }

    @Test
    void shouldDeclineWhenCardStatusIsNull() {
        // Arrange
        givenCard(cardWithStatus(null));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
    }

    @Test
    void shouldDeclineWhenCardExpiredByDate() {
        // Arrange
        YearMonth expiredMonth = YearMonth.from(requestTime.atZone(ZoneOffset.UTC)).minusMonths(1);
        CardModel expiredCard = cardWithStatusAndExpiry(CardModelStatus.ACTIVE, expiredMonth);
        givenCard(expiredCard);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_EXPIRED);
    }

    @Test
    void shouldApproveWhenBalanceEqualsAmount() {
        // Arrange
        givenCard(cardWithBalance(CardModelStatus.ACTIVE, AMOUNT));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(100L);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
    }

    @Test
    void shouldDeclineWhenBalanceBelowAmount() {
        // Arrange
        givenCard(cardWithBalance(CardModelStatus.ACTIVE, AMOUNT.subtract(BigDecimal.ONE)));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_INSUFFICIENT_FUNDS);
    }

    @Test
    void shouldDeclineAndSkipReservationWhenLimitExceeded() {
        // Arrange
        givenCard(cardWithStatus(CardModelStatus.ACTIVE));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_EXCEEDS_LIMIT);
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldApproveWhenLimitUpdateSucceedsAfterDuplicateKeyRetry() {
        // Arrange
        givenCard(cardWithStatus(CardModelStatus.ACTIVE));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(100L);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
    }

    @Test
    void shouldDeclineWhenLimitStorageIsUnavailable() {
        // Arrange
        givenCard(cardWithStatus(CardModelStatus.ACTIVE));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("limit_usage is down"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldDeclineWhenCardNotFound() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
    }

    @Test
    void shouldDeclineWhenCardRequestIsInvalid() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new InvalidGetCardRequestException("bad pan"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
    }

    @Test
    void shouldDeclineWhenCardManagementIsUnavailable() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new ServiceUnavailableException("cms down"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldDeclineWhenCardManagementFailsInternally() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new InternalCardManagerException("cms error"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldDeclineWhenGetCardFails() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new GetCardException("unexpected status"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldDeclineAsBlockedWhenCardManagementRequiresPayment() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new PaymentRequiredException("payment required"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_BLOCKED");
    }

    @Test
    void shouldDeclineUnknownWhenGetCardThrowsUnexpectedException() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new IllegalStateException("boom"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
    }

    @Test
    void shouldDeclineInsufficientFundsWhenReservationRejected() {
        // Arrange
        givenReservationReadyCard();
        doThrow(new InsufficientFundsException("no funds"))
                .when(cardManagementClient).reserve(any(), any(), any());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_INSUFFICIENT_FUNDS);
    }

    @Test
    void shouldDeclineWhenReservationFails() {
        // Arrange
        givenReservationReadyCard();
        doThrow(new ReserveException("reserve failed"))
                .when(cardManagementClient).reserve(any(), any(), any());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("RESERVATION_FAILED");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldDeclineCardNotFoundWhenReservationReportsMissingCard() {
        // Arrange
        givenReservationReadyCard();
        doThrow(new CardNotFoundException(PAN))
                .when(cardManagementClient).reserve(any(), any(), any());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void shouldDeclineCardNotFoundWhenReservationRequestIsInvalid() {
        // Arrange
        givenReservationReadyCard();
        doThrow(new InvalidReserveRequestException("bad reserve request"))
                .when(cardManagementClient).reserve(any(), any(), any());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
    }

    @Test
    void shouldDeclineUnknownWhenReservationThrowsUnexpectedException() {
        // Arrange
        givenReservationReadyCard();
        doThrow(new IllegalStateException("boom"))
                .when(cardManagementClient).reserve(any(), any(), any());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
    }

    @Test
    void shouldApproveRollbackWhenCardManagementAcceptsIt() {
        // Act
        RollbackResponse response = authService.rollback(rollbackRequest(), requestTime);

        // Assert
        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(RollbackResponse.CODE_SUCCESS);
        verify(cardManagementClient).rollback(any());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineRollbackWhenTransactionAlreadyRolledBack() {
        // Arrange
        doThrow(new RollbackConflictException("already rolled back"))
                .when(cardManagementClient).rollback(any());

        // Act
        RollbackResponse response = authService.rollback(rollbackRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("ALREADY_ROLLED_BACK");
        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
    }

    @Test
    void shouldDeclineRollbackWhenTransactionNotFound() {
        // Arrange
        doThrow(new CardNotFoundException(PAN)).when(cardManagementClient).rollback(any());

        // Act
        RollbackResponse response = authService.rollback(rollbackRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(response.responseCode()).isEqualTo(RollbackResponse.CODE_TRANSACTION_NOT_FOUND);
    }

    @Test
    void shouldDeclineRollbackWhenRequestIsInvalid() {
        // Arrange
        doThrow(new InvalidRollbackRequestException("bad request")).when(cardManagementClient).rollback(any());

        // Act
        RollbackResponse response = authService.rollback(rollbackRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("TRANSACTION_NOT_FOUND");
    }

    @Test
    void shouldDeclineRollbackWhenCardManagementIsUnavailable() {
        // Arrange
        doThrow(new ServiceUnavailableException("cms down")).when(cardManagementClient).rollback(any());

        // Act
        RollbackResponse response = authService.rollback(rollbackRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void shouldDeclineRollbackWhenRollbackFails() {
        // Arrange
        doThrow(new RollbackFailureException("rollback failed")).when(cardManagementClient).rollback(any());

        // Act
        RollbackResponse response = authService.rollback(rollbackRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("ROLLBACK_FAILED");
        assertThat(response.responseCode()).isEqualTo(RollbackResponse.CODE_SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldDeclineUnknownWhenRollbackThrowsUnexpectedException() {
        // Arrange
        doThrow(new IllegalStateException("boom")).when(cardManagementClient).rollback(any());

        // Act
        RollbackResponse response = authService.rollback(rollbackRequest(), requestTime);

        // Assert
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
    }

    private void givenCard(CardModel card) {
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
    }

    private void givenReservationReadyCard() {
        givenCard(cardWithStatus(CardModelStatus.ACTIVE));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(100L);
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000",
                AMOUNT, "643", requestTime,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID);
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return cardWithStatusAndExpiry(status, cardExpiryAfter(requestTime));
    }

    private CardModel cardWithBalance(CardModelStatus status, BigDecimal availableBalance) {
        return new CardModel(
                CARD_ID, PAN, BIN, "IVAN IVANOV",
                cardExpiryAfter(requestTime), status, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, availableBalance,
                ISSUER_ID, CREATED_AT);
    }

    private YearMonth cardExpiryAfter(Instant referenceTime) {
        return YearMonth.from(referenceTime.atZone(ZoneOffset.UTC)).plusYears(2);
    }

    private CardModel cardWithStatusAndExpiry(CardModelStatus status, YearMonth expiryDate) {
        return new CardModel(
                CARD_ID, PAN, BIN, "IVAN IVANOV",
                expiryDate, status, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, BALANCE,
                ISSUER_ID, CREATED_AT);
    }
}
