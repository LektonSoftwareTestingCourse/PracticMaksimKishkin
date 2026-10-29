package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String MERCHANT_ID = "MERCH0000000002";
    private static final Instant REQUEST_TIME = Instant.parse("2026-10-29T10:00:00Z");

    private TransactionRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TransactionRequestValidator();
    }

    @Test
    void shouldAcceptValidRequest() {
        // Act / Assert
        assertThatCode(() -> validator.validate(validRequest())).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNullRequest() {
        // Act / Assert
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("Request body is required");
    }

    @ParameterizedTest(name = "missing {1}")
    @MethodSource("requestsWithMissingFields")
    void shouldRejectRequestWithMissingField(AuthorizationRequest request, String fieldName) {
        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining(fieldName);
    }

    @Test
    void shouldRejectRequestWithUnsupportedMti() {
        // Arrange
        AuthorizationRequest request = baseRequest().mti("0200").build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'mti' must be '0100'");
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"400000123456789", "40000012345678901", "40000012345678AB"})
    void shouldRejectPanThatIsNotExactly16Digits(String pan) {
        // Arrange
        AuthorizationRequest request = baseRequest().pan(pan).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'pan' must be exactly 16 digits");
    }

    @Test
    void shouldRejectNullAmount() {
        // Arrange
        AuthorizationRequest request = baseRequest().amount(null).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'amount' is required");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "-1", "-1000"})
    void shouldRejectNonPositiveAmount(String amount) {
        // Arrange
        AuthorizationRequest request = baseRequest().amount(new BigDecimal(amount)).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'amount' must be > 0");
    }

    @ParameterizedTest(name = "currencyCode={0}")
    @ValueSource(strings = {"64", "6430"})
    void shouldRejectCurrencyCodeWithWrongLength(String currencyCode) {
        // Arrange
        AuthorizationRequest request = baseRequest().currencyCode(currencyCode).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc={0}")
    @ValueSource(strings = {"504", "50451", "50A5"})
    void shouldRejectMccThatIsNotFourDigits(String mcc) {
        // Arrange
        AuthorizationRequest request = baseRequest().mcc(mcc).build();

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'mcc' must be exactly 4 digits");
    }

    static Stream<Arguments> requestsWithMissingFields() {
        return Stream.of(
                Arguments.of(baseRequest().mti(null).build(), "mti"),
                Arguments.of(baseRequest().stan(null).build(), "stan"),
                Arguments.of(baseRequest().pan(null).build(), "pan"),
                Arguments.of(baseRequest().processingCode(" ").build(), "processingCode"),
                Arguments.of(baseRequest().currencyCode(null).build(), "currencyCode"),
                Arguments.of(baseRequest().transmissionDateTime(null).build(), "transmissionDateTime"),
                Arguments.of(baseRequest().terminalId(null).build(), "terminalId"),
                Arguments.of(baseRequest().merchantId(null).build(), "merchantId"),
                Arguments.of(baseRequest().mcc(null).build(), "mcc"),
                Arguments.of(baseRequest().acquirerId(null).build(), "acquirerId"));
    }

    private static AuthorizationRequest validRequest() {
        return baseRequest().build();
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder baseRequest() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan(STAN)
                .pan(PAN)
                .processingCode("000000")
                .amount(new BigDecimal("1000"))
                .currencyCode("643")
                .transmissionDateTime(REQUEST_TIME)
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId(MERCHANT_ID)
                .mcc("5045")
                .acquirerId("ACQ002")
                .issuerId("ISS001");
    }
}
