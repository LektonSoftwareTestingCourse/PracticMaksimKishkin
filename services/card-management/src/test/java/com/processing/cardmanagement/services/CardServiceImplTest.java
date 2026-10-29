package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEvent;
import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "123456789012";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal BALANCE = new BigDecimal("20000");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("8000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("25000");
    private static final UUID CARD_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int MAX_CREATION_RETRIES = 3;
    private static final int CARD_VALIDITY_PERIOD = 36;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceSettings settings;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    @Mock
    private RetryService retryService;

    @Mock
    private TransactionRunner transactionRunner;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                settings,
                defaults,
                panGenerator,
                eventNotifier,
                binIssuerService,
                retryService,
                transactionRunner);
        givenDirectTransactions();
    }

    @Test
    void shouldReturnCardWhenCardExists() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card()));

        // Act
        Card card = cardService.getCard(PAN);

        // Assert
        assertThat(card.pan()).isEqualTo(PAN);
    }

    @Test
    void shouldThrowWhenCardDoesNotExist() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> cardService.getCard(PAN))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldThrowWhenPageLimitExceedsMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(MAX_PAGE_LIMIT);

        // Act / Assert
        assertThatThrownBy(() -> cardService.getCards(MAX_PAGE_LIMIT + 1, 0L, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
    }

    @Test
    void shouldApplyDefaultPaginationWhenLimitAndOffsetAreMissing() {
        // Arrange
        when(defaults.pageLimit()).thenReturn(50);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(anyInt(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(card()));

        // Act
        List<Card> cards = cardService.getCards(null, null, null, null, null, null, null);

        // Assert
        assertThat(cards).hasSize(1);
        verify(cardRepository).findCards(50, 0L, null, null, null, null, null);
    }

    @Test
    void shouldPatchOnlyProvidedFields() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        cardService.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

        // Assert
        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(captor.getValue().dailyLimit()).isEqualByComparingTo(DAILY_LIMIT);
        assertThat(captor.getValue().availableBalance()).isEqualByComparingTo(BALANCE);
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    void shouldMarkCardAsDeleted() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        cardService.deleteCard(PAN);

        // Assert
        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(CardStatus.DELETED);
    }

    @Test
    void shouldReserveAmountWhenRrnIsUnique() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card reserved = cardService.reserve(PAN, AMOUNT, RRN);

        // Assert
        assertThat(reserved.availableBalance()).isEqualByComparingTo(BALANCE.subtract(AMOUNT));
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    void shouldThrowWhenReservationAlreadyExists() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        // Act / Assert
        assertThatThrownBy(() -> cardService.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
    }

    @Test
    void shouldRollbackReservation() {
        // Arrange
        Reservation reservation = new Reservation(PAN, AMOUNT, RRN);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(reservationRollbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card rolledBack = cardService.rollback(PAN, AMOUNT, RRN);

        // Assert
        assertThat(rolledBack.availableBalance()).isEqualByComparingTo(BALANCE.add(AMOUNT));
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    void shouldThrowWhenReservationNotFoundOnRollback() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> cardService.rollback(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void shouldThrowWhenBulkUpdateHasNoFilters() {
        // Act / Assert
        assertThatThrownBy(() -> cardService.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowWhenBulkUpdateHasBothFilters() {
        // Act / Assert
        assertThatThrownBy(() -> cardService.bulkUpdateStatus(
                List.of(BIN), List.of(PAN), CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateCardsSelectedByBins() {
        // Arrange
        when(cardRepository.findCardsByBinsForUpdate(List.of(BIN))).thenReturn(List.of(card()));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        int updated = cardService.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

        // Assert
        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(CardStatus.BLOCKED);
    }

    @Test
    void shouldUpdateCardsSelectedByPans() {
        // Arrange
        when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(card(), card()));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        int updated = cardService.bulkUpdateStatus(null, List.of(PAN), CardStatus.INACTIVE);

        // Assert
        assertThat(updated).isEqualTo(2);
    }

    @Test
    void shouldCreateCardWithGeneratedPan() {
        // Arrange
        Card saved = card();
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(settings.cardValidityPeriod()).thenReturn(CARD_VALIDITY_PERIOD);
        when(settings.maxCardCreationRetries()).thenReturn(MAX_CREATION_RETRIES);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(cardRepository.create(any())).thenReturn(saved);

        // Act
        Card created = cardService.createCard(BIN, "IVAN IVANOV", "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);

        // Assert
        assertThat(created).isSameAs(saved);
        verify(panGenerator).generatePan(BIN);
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    void shouldCountAllCardsAndPublishMetric() {
        // Arrange
        when(cardRepository.countAllCards()).thenReturn(7L);

        // Act
        long count = cardService.countAllCardsAndUpdate();

        // Assert
        assertThat(count).isEqualTo(7);
        verify(eventNotifier).notifyListeners(any(CardEvent.class));
    }

    @Test
    void shouldCountFilteredCards() {
        // Arrange
        when(cardRepository.countCardsFiltered(CardStatus.ACTIVE, BIN, null, null, null)).thenReturn(3L);

        // Act
        long count = cardService.countCardsFiltered(CardStatus.ACTIVE, BIN, null, null, null);

        // Assert
        assertThat(count).isEqualTo(3);
    }

    private void givenDirectTransactions() {
        lenient().doAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        }).when(transactionRunner).runSupplier(any());

        lenient().doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(transactionRunner).run(any());

        lenient().doAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        }).when(retryService).supply(anyInt(), any());
    }

    private Card card() {
        return new Card(
                CARD_ID, PAN, BIN, "IVAN IVANOV",
                YearMonth.now().plusYears(2), CardStatus.ACTIVE, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, BALANCE,
                ISSUER_ID, CREATED_AT);
    }
}
