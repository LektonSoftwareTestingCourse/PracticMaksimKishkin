package com.processing.cardmanagement.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private static final String BIN = "400000";

    private LuhnValidator luhnValidator;

    @BeforeEach
    void setUp() {
        luhnValidator = new LuhnValidator();
    }

    @ParameterizedTest(name = "pan={0} => valid={1}")
    @CsvSource({
            "4000001234560001, true",
            "4000001234560002, false",
            "4000000000000002, true",
            "1234567812345670, true",
            "1234567812345678, false"
    })
    void shouldDetectControlSumByLuhnAlgorithm(String pan, boolean expectedValid) {
        // Act
        boolean valid = luhnValidator.isValid(pan);

        // Assert
        assertThat(valid).isEqualTo(expectedValid);
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"4000000000000002", "0000000000000000", "79927398713"})
    void shouldAcceptPanWithKnownValidControlSum(String pan) {
        // Act
        boolean valid = luhnValidator.isValid(pan);

        // Assert
        assertThat(valid).isTrue();
    }

    @Test
    void shouldGeneratePanWithGivenBinAndValidControlSum() {
        // Act
        String pan = luhnValidator.generatePan(BIN);

        // Assert
        assertThat(pan).hasSize(16).startsWith(BIN);
        assertThat(luhnValidator.isValid(pan)).isTrue();
    }

    @Test
    void shouldGenerateDifferentPansForSameBin() {
        // Act
        String first = luhnValidator.generatePan(BIN);
        String second = luhnValidator.generatePan(BIN);

        // Assert
        assertThat(first).isNotEqualTo(second);
    }
}
