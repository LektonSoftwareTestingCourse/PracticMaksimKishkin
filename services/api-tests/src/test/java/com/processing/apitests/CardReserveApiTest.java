package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Внутренние операции над картой (источник — контроллер CardController, в спецификации отсутствуют):
 * reserve, rollback, bulk-update. Эти эндпоинты — часть цепочки авторизации, поэтому их
 * статус-коды и эффекты проверяются отдельно.
 */
@DisplayName("Cards API: reserve / rollback / bulk-update")
class CardReserveApiTest extends ApiTestBase {

    private static final long BALANCE = 100_000L;

    private static Map<String, Object> reserve(String rrn, long amount) {
        return Map.of("amount", amount, "rrn", rrn);
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/reserve -> 200, баланс уменьшается на сумму")
    void reserveReducesAvailableBalance() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, BALANCE);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(reserve(uniqueRrn(), 30_000L)))
                .when()
                .post("/api/cards/{pan}/reserve")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(pan))
                .body("availableBalance", equalTo(70000));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/reserve при недостатке средств -> 402")
    void reserveWithInsufficientFundsReturnsPaymentRequired() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, 10_000L);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(reserve(uniqueRrn(), 20_000L)))
                .when()
                .post("/api/cards/{pan}/reserve")
                .then()
                .statusCode(402)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("InsufficientFundsException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/reserve с повторным RRN -> 409")
    void reserveWithDuplicateRrnReturnsConflict() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, BALANCE);
        String rrn = uniqueRrn();

        gateway().pathParam("pan", pan).body(toJson(reserve(rrn, 1_000L)))
                .when().post("/api/cards/{pan}/reserve").then().statusCode(200);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(reserve(rrn, 500L)))
                .when()
                .post("/api/cards/{pan}/reserve")
                .then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("ReservationAlreadyExistsException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/reserve несуществующей карты -> 404")
    void reserveUnknownCardReturnsNotFound() {
        gateway()
                .pathParam("pan", unusedPan())
                .body(toJson(reserve(uniqueRrn(), 1_000L)))
                .when()
                .post("/api/cards/{pan}/reserve")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/reserve: amount=0 -> 400 (граница допустимого значения)")
    void reserveRejectsNonPositiveAmount() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, BALANCE);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(reserve(uniqueRrn(), 0L)))
                .when()
                .post("/api/cards/{pan}/reserve")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsString("must be greater than 0"));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/reserve: RRN неверной длины -> 400")
    void reserveRejectsInvalidRrn() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, BALANCE);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(reserve("123", 1_000L)))
                .when()
                .post("/api/cards/{pan}/reserve")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsString("RRN number must contain exactly 12 digits"));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/rollback -> 200, баланс восстанавливается")
    void rollbackRestoresAvailableBalance() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, BALANCE);
        String rrn = uniqueRrn();
        assertEquals200Reserve(pan, rrn);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(Map.of("amount", 30_000L, "rrn", rrn, "pan", pan)))
                .when()
                .post("/api/cards/{pan}/rollback")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("availableBalance", equalTo(100000));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/rollback по неизвестному RRN -> 404")
    void rollbackUnknownRrnReturnsNotFound() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, BALANCE);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(Map.of("amount", 1_000L, "rrn", uniqueRrn(), "pan", pan)))
                .when()
                .post("/api/cards/{pan}/rollback")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("ReservationNotFoundException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("POST /api/cards/{pan}/rollback повторно -> 409 RollbackAlreadySatisfied")
    void rollbackTwiceReturnsConflict() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, BALANCE);
        String rrn = uniqueRrn();
        assertEquals200Reserve(pan, rrn);
        Map<String, Object> rollback = Map.of("amount", 30_000L, "rrn", rrn, "pan", pan);

        gateway().pathParam("pan", pan).body(toJson(rollback))
                .when().post("/api/cards/{pan}/rollback").then().statusCode(200);

        gateway()
                .pathParam("pan", pan)
                .body(toJson(rollback))
                .when()
                .post("/api/cards/{pan}/rollback")
                .then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("RollbackAlreadySatisfiedException"));
    }

    @Test
    @DisplayName("POST /api/cards/bulk-update по списку PAN -> 200, updated=1")
    void bulkUpdateByPansReturnsUpdatedCount() {
        String pan = createCard();

        gateway()
                .body(toJson(Map.of("pans", List.of(pan), "status", "BLOCKED")))
                .when()
                .post("/api/cards/bulk-update")
                .then()
                .statusCode(200)
                .body("updated", equalTo(1));

        gatewayGet().pathParam("pan", pan).when().get("/api/cards/{pan}")
                .then().statusCode(200).body("status", equalTo("BLOCKED"));
    }

    @Test
    @DisplayName("POST /api/cards/bulk-update: одновременно bins и pans -> 400")
    void bulkUpdateRejectsBothSelectors() {
        gateway()
                .body(toJson(Map.of(
                        "bins", List.of(BIN),
                        "pans", List.of(unusedPan()),
                        "status", "BLOCKED")))
                .when()
                .post("/api/cards/bulk-update")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("IllegalArgumentException"))
                .body("message", containsString("Only one of bin or pans must be provided"));
    }

    @Test
    @DisplayName("POST /api/cards/bulk-update без селектора -> 400")
    void bulkUpdateRejectsEmptySelector() {
        gateway()
                .body(toJson(Map.of("status", "BLOCKED")))
                .when()
                .post("/api/cards/bulk-update")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("IllegalArgumentException"))
                .body("message", containsString("Either bin or pans must be provided"));
    }

    @Test
    @DisplayName("POST /api/cards/bulk-update с BIN неверного формата -> 400")
    void bulkUpdateRejectsInvalidBinFormat() {
        gateway()
                .body(toJson(Map.of("bins", List.of("12"), "status", "BLOCKED")))
                .when()
                .post("/api/cards/bulk-update")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("message", containsString("BIN number must contain exactly 6 digits"));
    }

    @Test
    @DisplayName("POST /api/cards/bulk-update: количество затронутых карт не отрицательно")
    void bulkUpdateReturnsNonNegativeCount() {
        gateway()
                .body(toJson(Map.of("pans", List.of(unusedPan()), "status", "BLOCKED")))
                .when()
                .post("/api/cards/bulk-update")
                .then()
                .statusCode(200)
                .body("updated", greaterThanOrEqualTo(0));
    }

    private void assertEquals200Reserve(String pan, String rrn) {
        gateway()
                .pathParam("pan", pan)
                .body(toJson(reserve(rrn, 30_000L)))
                .when()
                .post("/api/cards/{pan}/reserve")
                .then()
                .statusCode(200);
    }
}
