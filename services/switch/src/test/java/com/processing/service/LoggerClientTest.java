package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoggerClientTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String RRN = "123456789012";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-29T10:00:00Z");

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    void shouldPublishTransactionToTransactionLogQueue() {
        // Arrange
        TransactionRequest transaction = transaction();

        // Act
        boolean published = loggerClient.log(transaction);

        // Assert
        assertThat(published).isTrue();
        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }

    @Test
    void shouldReturnFalseWhenBrokerRejectsTransaction() {
        // Arrange
        doThrow(new AmqpException("broker is down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Act
        boolean published = loggerClient.log(transaction());

        // Assert
        assertThat(published).isFalse();
    }

    private TransactionRequest transaction() {
        return new TransactionRequest(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "0100", STAN, RRN, PAN, "000000",
                new BigDecimal("1000"), "643", "TERM0001", "POS", "MERCH0000000002",
                "5045", "ACQ002", "ISS001", new BigDecimal("1.50"), TransactionStatus.APPROVED,
                null, "ABC123", 42, TRANSMISSION_TIME, TRANSMISSION_TIME.plusSeconds(1));
    }
}
