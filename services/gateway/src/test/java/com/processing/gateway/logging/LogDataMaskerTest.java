package com.processing.gateway.logging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogDataMaskerTest {

    private LogDataMasker logDataMasker;

    @BeforeEach
    void setUp() {
        logDataMasker = new LogDataMasker();
    }

    @Test
    void shouldMaskPanInJsonBody() {
        // Arrange
        String body = "{\"pan\":\"4000001234567890\"}";

        // Act
        String masked = logDataMasker.maskData(body);

        // Assert
        assertThat(masked).contains("400000****7890").doesNotContain("4000001234567890");
    }

    @Test
    void shouldMaskCvvInJsonBody() {
        // Arrange
        String body = "{\"cvv\":\"123\"}";

        // Act
        String masked = logDataMasker.maskData(body);

        // Assert
        assertThat(masked).contains("\"***\"").doesNotContain("123");
    }

    @Test
    void shouldMaskPanAndCvvInSameBody() {
        // Arrange
        String body = "{\"pan\":\"4000001234567890\",\"cvv\":\"9876\"}";

        // Act
        String masked = logDataMasker.maskData(body);

        // Assert
        assertThat(masked).contains("400000****7890").contains("\"***\"");
        assertThat(masked).doesNotContain("1234567890").doesNotContain("9876");
    }

    @ParameterizedTest(name = "body={0}")
    @ValueSource(strings = {
            "{\"amount\":\"1000\"}",
            "{\"pan\":\"400000\"}",
            "{}"
    })
    void shouldKeepBodyWithoutSensitiveDataUnchanged(String body) {
        // Act
        String masked = logDataMasker.maskData(body);

        // Assert
        assertThat(masked).isEqualTo(body);
    }
}
