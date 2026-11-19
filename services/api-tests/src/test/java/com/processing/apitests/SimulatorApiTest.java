package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Контракт симуляторов (docs/api-spec.md, раздел 4, и контроллеры сервисов).
 * Позитивные прогоны идут с count=1, чтобы не искажать данные стенда.
 */
@DisplayName("Simulator API")
class SimulatorApiTest extends ApiTestBase {

    @Test
    @DisplayName("POST /api/simulator/terminal/run -> 200 со статистикой approved/declined")
    void terminalRunReturnsStatistics() {
        createCard();

        gateway()
                .body(toJson(Map.of("count", 1, "scenario", "normal", "tps", 1000)))
                .when()
                .post("/api/simulator/terminal/run")
                .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(1))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("transactions.size()", equalTo(1));
    }

    @Test
    @DisplayName("POST /api/simulator/terminal/run с count=0 -> 400")
    void terminalRunRejectsZeroCount() {
        gateway()
                .body(toJson(Map.of("count", 0, "scenario", "normal", "tps", 1000)))
                .when()
                .post("/api/simulator/terminal/run")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Validation failed"))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    @DisplayName("Непрерывный режим: старт 200, повторный старт 422, стоп 200")
    void terminalContinuousLifecycle() {
        createCard();
        Map<String, Object> startBody = Map.of("tps", 1000, "transactionType", "NORMAL");

        try {
            gateway().body(toJson(startBody)).when().post("/api/simulator/terminal/start-continuous")
                    .then().statusCode(200);

            gateway().body(toJson(startBody)).when().post("/api/simulator/terminal/start-continuous")
                    .then()
                    .statusCode(422)
                    .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                    .body("error", equalTo("Illegal state"))
                    .body("serviceName", equalTo("terminal-simulator"));
        } finally {
            gateway().when().post("/api/simulator/terminal/stop").then().statusCode(200);
        }
    }

    @Test
    @DisplayName("POST /api/simulator/terminal/start-continuous без transactionType -> 400")
    void startContinuousRejectsMissingTransactionType() {
        gateway()
                .body(toJson(Map.of("tps", 1000)))
                .when()
                .post("/api/simulator/terminal/start-continuous")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Validation failed"))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    @DisplayName("POST /api/simulator/terminal/stop при остановленном режиме -> 200")
    void stopIsIdempotent() {
        gateway()
                .when()
                .post("/api/simulator/terminal/stop")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/run -> 200 со статистикой")
    void merchantRunReturnsStatistics() {
        createCard();

        gateway()
                .body(toJson(Map.of("count", 1, "scenario", "grocery", "tps", 1000)))
                .when()
                .post("/api/simulator/merchant/run")
                .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(1))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("transactions.size()", equalTo(1));
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/run с count=0 -> 400")
    void merchantRunRejectsZeroCount() {
        gateway()
                .body(toJson(Map.of("count", 0, "scenario", "grocery")))
                .when()
                .post("/api/simulator/merchant/run")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Invalid request"))
                .body("serviceName", equalTo("Merchant acquirer simulator"))
                .body("message", containsString("count"));
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/run без scenario -> 400")
    void merchantRunRejectsMissingScenario() {
        gateway()
                .body(toJson(Map.of("count", 1)))
                .when()
                .post("/api/simulator/merchant/run")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Invalid request"))
                .body("message", containsString("Scenario is required"));
    }

    // DEF-12: маршрут /api/simulator/merchant/** не покрывает /merchants.
    @Test
    @DisplayName("GET /api/simulator/merchants (напрямую, 8084) -> 200, непустой список")
    void merchantsListIsAvailable() {
        merchantApi()
                .when()
                .get("/api/simulator/merchants")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("GET /api/simulator/merchants через Gateway -> 404 (DEF-12: маршрут не совпадает)")
    void merchantsListIsNotExposedByGateway() {
        gatewayGet()
                .when()
                .get("/api/simulator/merchants")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/fee для неизвестной транзакции -> 404")
    void acquirerFeeUnknownTransactionReturnsNotFound() {
        Map<String, Object> feeRequest = Map.of(
                "transmissionDateTime", "2026-06-01T10:30:00Z",
                "pan", unusedPan(),
                "stan", uniqueStan(),
                "terminalId", "TERM0001",
                "amount", 1_000L);

        gateway()
                .body(toJson(feeRequest))
                .when()
                .post("/api/simulator/merchant/fee")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Resource not found"))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/fee без обязательных полей -> 400")
    void acquirerFeeMissingFieldsReturnsBadRequest() {
        gateway()
                .body(toJson(Map.of("amount", 1_000L)))
                .when()
                .post("/api/simulator/merchant/fee")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Invalid request"));
    }

    @Test
    @DisplayName("Терминальный симулятор возвращает ровно count ответов")
    void terminalRunResponseCountMatchesRequest() {
        createCard();

        int transactions = gateway()
                .body(toJson(Map.of("count", 2, "scenario", "normal", "tps", 1000)))
                .when()
                .post("/api/simulator/terminal/run")
                .then()
                .statusCode(200)
                .extract()
                .path("transactions.size()");

        assertEquals(2, transactions);
    }
}
