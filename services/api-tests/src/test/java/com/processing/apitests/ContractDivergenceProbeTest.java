package com.processing.apitests;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;

/**
 * Пробы расхождений: тест сформулирован по контракту и сейчас красный.
 * Снятие @Disabled делает пробу падающим тестом; отчёт — раздел 4.
 */
@DisplayName("Расхождения контракта и реализации (пробы)")
class ContractDivergenceProbeTest extends ApiTestBase {

    @Test
    @Disabled("DEF-01: спецификация обещает mti=0110, код возвращает 0100")
    void approvedMtiShouldBe0110PerSpecification() {
        String pan = createCard();

        gateway()
                .body(toJson(authorizationBody(pan, uniqueStan(), 1_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("mti", equalTo("0110"));
    }

    @Test
    @Disabled("DEF-04: спецификация обещает 201 на POST /api/internal/log, повтор даёт 200")
    void loggerReplayShouldReturn201PerSpecification() {
        String pan = createCard();
        String body = toJson(loggerTransaction(UUID.randomUUID().toString(), pan, uniqueStan(), 1_000L));

        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(201);
        loggerApi().body(body).when().post("/api/internal/log").then().statusCode(201);
    }

    @Test
    @Disabled("DEF-09: DELETE не инвалидирует кеш Gateway — чтение отдаёт устаревшую карту")
    void deleteShouldInvalidateGatewayCache() {
        String pan = createCard();
        gatewayGet().pathParam("pan", pan).when().get("/api/cards/{pan}").then().statusCode(200);
        gateway().pathParam("pan", pan).when().delete("/api/cards/{pan}").then().statusCode(204);

        gatewayGet()
                .pathParam("pan", pan)
                .when()
                .get("/api/cards/{pan}")
                .then()
                .statusCode(404);
    }

    @Test
    @Disabled("DEF-03: Gateway не проверяет длину stan, хотя спецификация объявляет String(6)")
    void gatewayShouldRejectStanOfWrongLength() {
        String pan = createCard();

        gateway()
                .body(toJson(authorizationBody(pan, "123", 1_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(400);
    }

    @Test
    @Disabled("DEF-08: ошибка валидации Authorization не имеет формата ErrorResponse")
    void internalAuthorizationValidationErrorShouldUseErrorResponse() {
        gateway().body(toJson(authorizationBody(createCard(), uniqueStan(), 1_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200);

        authorizationApi()
                .body(toJson(authorizationBody(unusedPan(), uniqueStan(), 1_000L)))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("authorization"));
    }

    @Test
    @Disabled("DEF-10: неподдерживаемый метод для /api/cards должен давать 405, а не 500")
    void unsupportedMethodShouldReturnMethodNotAllowed() {
        gateway()
                .body(toJson(Map.of("status", "BLOCKED")))
                .when()
                .put("/api/cards")
                .then()
                .statusCode(405);
    }
}
