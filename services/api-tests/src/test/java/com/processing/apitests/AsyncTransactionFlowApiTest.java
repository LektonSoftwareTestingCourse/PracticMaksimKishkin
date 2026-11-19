package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Асинхронный контракт: Gateway → Switch → RabbitMQ → Transaction Logger.
 */
@DisplayName("Сквозная цепочка и асинхронные эффекты")
class AsyncTransactionFlowApiTest extends ApiTestBase {

    @Test
    @DisplayName("Транзакция из Gateway становится доступной поиску (сквозная цепочка)")
    void approvedTransactionReachesLogger() {
        String pan = createCard();
        String stan = uniqueStan();

        Map<String, Object> response = gateway()
                .body(toJson(authorizationBody(pan, stan, 150_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .extract()
                .jsonPath()
                .getMap("$");

        awaitTransactionVisible(pan, stan);
        Map<String, Object> stored = findStoredTransaction(pan, stan);

        assertEquals(stan, stored.get("stan"));
        assertEquals(pan, stored.get("pan"));
        assertEquals("APPROVED", stored.get("status"));
        assertEquals(0, new BigDecimal(String.valueOf(stored.get("amount")))
                .compareTo(new BigDecimal("150000")), "Сумма должна сохраниться без искажений");
        assertEquals("ISS001", stored.get("issuerId"));
        assertEquals(response.get("rrn"), stored.get("rrn"));
        assertEquals(response.get("authCode"), stored.get("authCode"));
    }

    @Test
    @DisplayName("Отклонённая транзакция тоже сохраняется — с причиной отказа")
    void declinedTransactionReachesLoggerWithReason() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, 1_000L);
        String stan = uniqueStan();

        gateway()
                .body(toJson(authorizationBody(pan, stan, 9_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));

        awaitTransactionVisible(pan, stan);
        Map<String, Object> stored = findStoredTransaction(pan, stan);

        assertEquals("DECLINED", stored.get("status"));
        assertEquals("INSUFFICIENT_FUNDS", stored.get("declineReason"));
        assertNull(stored.get("rrn"));
        assertNull(stored.get("authCode"));
    }

    @Test
    @DisplayName("Отказ валидации Gateway не оставляет эффекта в хранилище")
    void gatewayValidationFailureLeavesNoTrace() {
        String pan = createCard();
        String stan = uniqueStan();
        Map<String, Object> invalidBody = authorizationBody(pan, stan, 1_000L);
        invalidBody.put("mcc", "abc");

        gateway()
                .body(toJson(invalidBody))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"));

        await()
                .during(Duration.ofSeconds(4))
                .atMost(Duration.ofSeconds(6))
                .pollDelay(Duration.ofMillis(500))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> storedCount(pan, stan) == 0);

        assertEquals(0, storedCount(pan, stan), "Отклонённый Gateway запрос не должен доходить до Logger");
    }
}
