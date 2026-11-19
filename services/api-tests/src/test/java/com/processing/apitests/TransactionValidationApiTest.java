package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Валидация запроса авторизации на Gateway (TransactionRequestValidator).
 * Один невалидный класс — один тест: в запросе нарушено ровно одно правило.
 */
@DisplayName("Authorization: валидация запроса на Gateway")
class TransactionValidationApiTest extends ApiTestBase {

    static Stream<String> requiredFields() {
        return Stream.of(
                "mti", "stan", "pan", "processingCode", "currencyCode", "transmissionDateTime",
                "terminalId", "merchantId", "mcc", "acquirerId", "amount");
    }

    @ParameterizedTest(name = "отсутствует {0} -> 400")
    @MethodSource("requiredFields")
    @DisplayName("Каждое обязательное поле запроса проверяется отдельно")
    void missingRequiredFieldIsRejected(String field) {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put(field, null);

        assertValidationError(body, "Field '" + field + "' is required");
    }

    @ParameterizedTest(name = "pan={0} -> 400")
    @ValueSource(strings = {"123", "400000123456000", "40000012345600001", "400000123456000A"})
    @DisplayName("PAN: классы эквивалентности неверной длины и нецифровых значений")
    void invalidPanClassesAreRejected(String pan) {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("pan", pan);

        assertValidationError(body, "Field 'pan' must be exactly 16 digits");
    }

    @Test
    @DisplayName("PAN пустой -> 400 «Field 'pan' is required»")
    void emptyPanIsTreatedAsMissing() {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("pan", "");

        assertValidationError(body, "Field 'pan' is required");
    }

    @Test
    @DisplayName("mti отличен от 0100 -> 400")
    void wrongMtiIsRejected() {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("mti", "0200");

        assertValidationError(body, "Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "amount={0} -> 400")
    @ValueSource(longs = {0L, -1L})
    @DisplayName("amount: граница «больше нуля» (0 и отрицательное)")
    void nonPositiveAmountIsRejected(long amount) {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("amount", amount);

        assertValidationError(body, "Field 'amount' must be > 0");
    }

    @ParameterizedTest(name = "currencyCode={0} -> 400")
    @ValueSource(strings = {"64", "6430"})
    @DisplayName("currencyCode: длина ровно 3 символа")
    void invalidCurrencyLengthIsRejected(String currencyCode) {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("currencyCode", currencyCode);

        assertValidationError(body, "Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc={0} -> 400")
    @ValueSource(strings = {"541", "54111", "54a1"})
    @DisplayName("mcc: ровно 4 цифры")
    void invalidMccIsRejected(String mcc) {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("mcc", mcc);

        assertValidationError(body, "Field 'mcc' must be exactly 4 digits");
    }

    @Test
    @DisplayName("Тело не является JSON -> 400 VALIDATION_ERROR")
    void malformedJsonIsRejected() {
        gateway()
                .body("{ this is not json")
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Request body must be valid JSON"))
                .body("serviceName", equalTo("gateway"))
                .body("retryAfterMs", nullValue());
    }

    // DEF-03: спецификация объявляет stan String(6), а Gateway проверяет
    // только непустоту; отказ Authorization Switch маскирует как 05.
    @Test
    @DisplayName("stan неверной длины проходит Gateway (DEF-03)")
    void shortStanPassesGatewayValidation() {
        Map<String, Object> body = authorizationBody(createCard(), "123", 1_000L);

        gateway()
                .body(toJson(body))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("Authorization service unavailable"));
    }

    @Test
    @DisplayName("GET /api/transactions (неизвестный путь) -> 404")
    void unsupportedPathReturnsNotFound() {
        // 404 формирует либо Gateway (нет маршрута), либо Logger (нет ресурса),
        // поэтому проверяется только статус: формат тела у них различается.
        gatewayGet()
                .when()
                .get("/api/transactions")
                .then()
                .statusCode(404);
    }

    private void assertValidationError(Map<String, Object> body, String expectedMessage) {
        gateway()
                .body(toJson(body))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo(expectedMessage))
                .body("serviceName", equalTo("gateway"))
                .body("retryAfterMs", nullValue());
    }

    @Test
    @DisplayName("Сообщение ошибки валидации содержит имя поля")
    void validationMessageNamesTheField() {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("merchantId", "");

        gateway()
                .body(toJson(body))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(400)
                .body("message", containsString("merchantId"));
    }
}
