package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    private static final String PAN = "4000001234567890";
    private static final String ISSUER_ID = "ISS001";
    private static final Instant REQUEST_TIME = Instant.parse("2026-10-29T10:00:00Z");

    @ParameterizedTest(name = "terminalId={0} => {1}")
    @CsvSource({
            "TERM,      TERM0000",
            "TERM0001,  TERM0001",
            "TERM000123, TERM0001"
    })
    void shouldNormalizeTerminalIdToEightCharacters(String source, String expected) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(source);

        // Assert
        assertThat(normalized).isEqualTo(expected).hasSize(8);
    }

    @ParameterizedTest(name = "terminalId={0}")
    @NullSource
    @ValueSource(strings = { "", "   " })
    void shouldKeepEmptyTerminalIdAsIs(String source) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(source);

        // Assert
        assertThat(normalized).isEqualTo(source);
    }

    @Test
    void shouldKeepMerchantIdWithExactLength() {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId("MERCH0000000002");

        // Assert
        assertThat(normalized).isEqualTo("MERCH0000000002");
    }

    @Test
    void shouldDropRedundantZeroAfterMerchantPrefix() {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId("MERCH0ABCDEFGHIJ");

        // Assert
        assertThat(normalized).isEqualTo("MERCHABCDEFGHIJ").hasSize(15);
    }

    @Test
    void shouldTruncateTooLongMerchantIdWithoutRedundantZero() {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId("XERCH0ABCDEFGHIJ");

        // Assert
        assertThat(normalized).hasSize(15);
        assertThat(normalized).isEqualTo("XERCH0ABCDEFGHI");
    }

    @Test
    void shouldTruncateMuchLongerMerchantId() {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId("MERCHANT123456789012345");

        // Assert
        assertThat(normalized).isEqualTo("MERCHANT1234567").hasSize(15);
    }

    @Test
    void shouldPadShortMerchantIdWithZeros() {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId("MERCH1");

        // Assert
        assertThat(normalized).isEqualTo("MERCH1000000000").hasSize(15);
    }

    @Test
    void shouldKeepNullMerchantIdAsIs() {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(null);

        // Assert
        assertThat(normalized).isNull();
    }

    @Test
    void shouldNormalizeTerminalAndMerchantIdsInRequest() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", PAN, "000000", new BigDecimal("1000"), "643", REQUEST_TIME,
                "TERM", "POS", "MERCH1", "5045", "ACQ002", ISSUER_ID);

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        // Assert
        assertThat(normalized.terminalId()).isEqualTo("TERM0000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH1000000000");
        assertThat(normalized.pan()).isEqualTo(PAN);
        assertThat(normalized.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(normalized.terminalType()).isEqualTo("POS");
        assertThat(normalized.amount()).isEqualByComparingTo("1000");
        assertThat(normalized.stan()).isEqualTo("000001");
    }

    @Test
    void shouldKeepIssuerIdEmptyWhenItWasNotRoutedYet() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", PAN, "000000", new BigDecimal("1000"), "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", null);

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        // Assert
        assertThat(normalized.issuerId()).isNull();
    }
}
