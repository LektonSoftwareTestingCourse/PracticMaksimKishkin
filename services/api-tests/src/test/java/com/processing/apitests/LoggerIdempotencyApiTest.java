package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Контракт POST /api/internal/log (Transaction Logger, порт 8088).
 * Поиск идёт через Gateway. Ключ дедупликации — id, исходы повтора: 201 / 200 /
 * 409.
 */
@DisplayName("Transaction Logger: идемпотентность /api/internal/log")
class LoggerIdempotencyApiTest extends ApiTestBase {

    @Test
    @DisplayName("Первая публикация -> 201 {id, status=stored}")
    void firstPublicationReturnsCreated() {
        String pan = createCard();
        String id = UUID.randomUUID().toString();

        loggerApi()
                .body(toJson(loggerTransaction(id, pan, uniqueStan(), 10_000L)))
                .when()
                .post("/api/internal/log")
                .then()
                .statusCode(201)
                .header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/stored-transaction.json"))
                .body("id", equalTo(id))
                .body("status", equalTo("stored"));
    }

    @Test
    @DisplayName("Повтор того же сообщения -> 200 и существующая запись")
    void replayReturnsExistingTransaction() {
        String pan = createCard();
        String id = UUID.randomUUID().toString();
        String body = toJson(loggerTransaction(id, pan, uniqueStan(), 10_000L));

        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(201);

        loggerApi()
                .body(body)
                .when()
                .post("/api/internal/log")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction.json"))
                .body("id", equalTo(id))
                .body("amount", equalTo(10000))
                .body("status", equalTo("APPROVED"));
    }

    @Test
    @DisplayName("Повтор с другим amount -> 409 Transaction conflict")
    void conflictingReplayReturnsConflict() {
        String pan = createCard();
        String id = UUID.randomUUID().toString();
        String stan = uniqueStan();

        loggerApi().body(toJson(loggerTransaction(id, pan, stan, 10_000L)))
                .when().post("/api/internal/log").then().statusCode(201);

        loggerApi()
                .body(toJson(loggerTransaction(id, pan, stan, 20_000L)))
                .when()
                .post("/api/internal/log")
                .then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Transaction conflict"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("retryAfterMs", equalTo("0"))
                .body("message", containsString(id));
    }

    @Test
    @DisplayName("После повтора в хранилище ровно одна запись с нашим id")
    void redeliveryStoresOnlyOneRecord() {
        String pan = createCard();
        String id = UUID.randomUUID().toString();
        String body = toJson(loggerTransaction(id, pan, uniqueStan(), 15_000L));

        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(201);
        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(200);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("transactions.findAll { it.id == '" + id + "' }.size()", equalTo(1))
                .body("transactions.findAll { it.id == '" + id + "' }[0].id", notNullValue());
    }

    @Test
    @DisplayName("Запись, отправленная напрямую в Logger, доступна поиску через Gateway")
    void storedTransactionIsVisibleThroughGatewaySearch() {
        String pan = createCard();
        String id = UUID.randomUUID().toString();
        String stan = uniqueStan();

        loggerApi().body(toJson(loggerTransaction(id, pan, stan, 33_000L)))
                .when().post("/api/internal/log").then().statusCode(201);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body("transactions.findAll { it.stan == '" + stan + "' }.size()", equalTo(1));
    }

    static Stream<Arguments> invalidTransactions() {
        return Stream.of(
                Arguments.of("id", null, "id: must not be null"),
                Arguments.of("stan", "123", "stan: stan must be exactly 6 digits"),
                Arguments.of("pan", "123", "pan: PAN number must contain exactly 16 digits"),
                Arguments.of("currencyCode", "64", "currencyCode: currencyCode must be exactly 3 digits"),
                Arguments.of("mcc", "abc", "mcc: mcc must be exactly 4 digits"),
                Arguments.of("amount", 0, "amount: must be greater than 0"),
                Arguments.of("status", null, "status: must not be null"));
    }

    @ParameterizedTest(name = "{0}={1} -> 400")
    @MethodSource("invalidTransactions")
    @DisplayName("Валидация TransactionRequest: каждое правило — отдельный класс")
    void invalidTransactionIsRejected(String field, Object value, String expectedMessage) {
        String pan = createCard();
        Map<String, Object> body = loggerTransaction(UUID.randomUUID().toString(), pan, uniqueStan(), 1_000L);
        body.put(field, value);

        loggerApi()
                .body(toJson(body))
                .when()
                .post("/api/internal/log")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Validation error"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString(expectedMessage));
    }

    @Test
    @DisplayName("Нечитаемое тело -> 400 Invalid request body")
    void malformedJsonIsRejected() {
        loggerApi()
                .body("{ broken json")
                .when()
                .post("/api/internal/log")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Invalid request body"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    @DisplayName("GET /api/internal/log -> 405 Method not allowed")
    void wrongMethodReturnsMethodNotAllowed() {
        loggerApi()
                .when()
                .get("/api/internal/log")
                .then()
                .statusCode(405)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Method not allowed"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    // DEF-11: PostgreSQL хранит timestamp(6), а matches сравнивает Instant точно —
    // повтор с наносекундами даёт 409 вместо 200.
    @Test
    @DisplayName("Повтор с наносекундной точностью -> ложный конфликт (DEF-11)")
    void replayWithSubMicrosecondPrecisionConflicts() {
        String pan = createCard();
        String id = UUID.randomUUID().toString();
        Instant timestamp = Instant.parse("2026-06-01T10:30:00.123456789Z");
        String body = toJson(loggerTransaction(id, pan, uniqueStan(), 1_000L, timestamp, timestamp));

        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(201);

        loggerApi()
                .body(body)
                .when()
                .post("/api/internal/log")
                .then()
                .statusCode(409)
                .body("error", equalTo("Transaction conflict"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    @DisplayName("Повторная публикация возвращает 200, а не 201 (DEF-04)")
    void replayStatusDiffersFromSpecification() {
        String pan = createCard();
        String body = toJson(loggerTransaction(UUID.randomUUID().toString(), pan, uniqueStan(), 1_000L));

        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(201);
        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(200);
    }
}
