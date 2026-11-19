package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Внутренние эндпоинты цепочки авторизации (источник — контроллеры сервисов):
 * Switch 8082 — /api/internal/route,
 * Authorization 8083 — /api/internal/authorize и /api/internal/rollback.
 * Напрямую сервис различает 404/403/422/503, через Gateway — 200 DECLINED.
 */
@DisplayName("Внутренние эндпоинты: switch и authorization")
class InternalEndpointsApiTest extends ApiTestBase {

    @Test
    @DisplayName("POST /api/internal/route (Switch) -> 200 APPROVED")
    void switchRouteApprovesKnownCard() {
        String pan = createCard();

        switchApi()
                .body(toJson(authorizationBody(pan, uniqueStan(), 10_000L)))
                .when()
                .post("/api/internal/route")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("^\\d{12}$"));
    }

    @Test
    @DisplayName("POST /api/internal/route: неизвестный BIN -> 200 DECLINED 14")
    void switchRouteDeclinesUnknownBin() {
        String pan = "999999" + String.format("%010d", 7654321098L);

        switchApi()
                .body(toJson(authorizationBody(pan, uniqueStan(), 10_000L)))
                .when()
                .post("/api/internal/route")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("mti", equalTo("0110"));
    }

    // DEF-07: Switch не валидирует контракт — пустое тело даёт DECLINED.
    @Test
    @DisplayName("POST /api/internal/route с пустым телом -> 200 DECLINED (DEF-07)")
    void switchRouteDoesNotValidateBody() {
        switchApi()
                .body("{}")
                .when()
                .post("/api/internal/route")
                .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"));
    }

    @Test
    @DisplayName("POST /api/internal/route с нечитаемым JSON -> 400")
    void switchRouteRejectsMalformedJson() {
        switchApi()
                .body("{")
                .when()
                .post("/api/internal/route")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("POST /api/internal/authorize -> 200 APPROVED с rrn и authCode")
    void internalAuthorizationApprovesCard() {
        String pan = createCard();
        String stan = uniqueStan();

        authorizationApi()
                .body(toJson(internalAuthorizationBody(pan, stan, 5_000L)))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("stan", equalTo(stan))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("^\\d{12}$"))
                .body("authCode", matchesPattern("^[A-Z0-9]{6}$"));
    }

    @Test
    @DisplayName("POST /api/internal/authorize: карта не найдена -> 404 / 14")
    void internalAuthorizationUnknownCardReturnsNotFound() {
        authorizationApi()
                .body(toJson(internalAuthorizationBody(unusedPan(), uniqueStan(), 5_000L)))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("rrn", nullValue());
    }

    @Test
    @DisplayName("POST /api/internal/authorize: карта заблокирована -> 403 / CARD_BLOCKED")
    void internalAuthorizationBlockedCardReturnsForbidden() {
        String pan = createCard();
        patchCard(pan, Map.of("status", "BLOCKED")).then().statusCode(200);

        authorizationApi()
                .body(toJson(internalAuthorizationBody(pan, uniqueStan(), 5_000L)))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(403)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_BLOCKED"));
    }

    @Test
    @DisplayName("POST /api/internal/authorize: недостаток средств -> 422 / 51")
    void internalAuthorizationInsufficientFundsReturnsUnprocessable() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, 1_000L);

        authorizationApi()
                .body(toJson(internalAuthorizationBody(pan, uniqueStan(), 5_000L)))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(422)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    // DEF-08: 400 у Authorization формирует Spring, тело не ErrorResponse.
    @Test
    @DisplayName("POST /api/internal/authorize без issuerId -> 400 (DEF-08: тело не ErrorResponse)")
    void internalAuthorizationWithoutIssuerReturnsBadRequest() {
        Map<String, Object> body = authorizationBody(createCard(), uniqueStan(), 1_000L);

        authorizationApi()
                .body(toJson(body))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(400)
                .body("status", equalTo(400));
    }

    @Test
    @DisplayName("POST /api/internal/authorize с terminalId неверной длины -> 400")
    void internalAuthorizationWrongTerminalLengthReturnsBadRequest() {
        Map<String, Object> body = internalAuthorizationBody(createCard(), uniqueStan(), 1_000L);
        body.put("terminalId", "T1");

        authorizationApi()
                .body(toJson(body))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("POST /api/internal/rollback -> 200 APPROVED, rrn возвращается")
    void internalRollbackApprovesReservation() {
        String pan = createCard();
        String rrn = authorizeAndGetRrn(pan, 5_000L);

        authorizationApi()
                .body(toJson(Map.of("rrn", rrn, "pan", pan, "amount", 5_000L)))
                .when()
                .post("/api/internal/rollback")
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", equalTo(rrn))
                .body("declineReason", nullValue());
    }

    @Test
    @DisplayName("POST /api/internal/rollback по неизвестному RRN -> 404 / 14")
    void internalRollbackUnknownRrnReturnsNotFound() {
        authorizationApi()
                .body(toJson(Map.of("rrn", uniqueRrn(), "pan", unusedPan(), "amount", 1_000L)))
                .when()
                .post("/api/internal/rollback")
                .then()
                .statusCode(404)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/internal/rollback повторно -> 409 / ALREADY_ROLLED_BACK")
    void internalRollbackTwiceReturnsConflict() {
        String pan = createCard();
        String rrn = authorizeAndGetRrn(pan, 5_000L);
        Map<String, Object> rollback = Map.of("rrn", rrn, "pan", pan, "amount", 5_000L);

        authorizationApi().body(toJson(rollback)).when().post("/api/internal/rollback")
                .then().statusCode(200);

        authorizationApi()
                .body(toJson(rollback))
                .when()
                .post("/api/internal/rollback")
                .then()
                .statusCode(409)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    @Test
    @DisplayName("POST /api/internal/rollback с RRN неверной длины -> 400")
    void internalRollbackInvalidRrnReturnsBadRequest() {
        authorizationApi()
                .body(toJson(Map.of("rrn", "1", "pan", unusedPan(), "amount", 1_000L)))
                .when()
                .post("/api/internal/rollback")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("POST /api/internal/rollback с amount=0 -> 400")
    void internalRollbackNonPositiveAmountReturnsBadRequest() {
        authorizationApi()
                .body(toJson(Map.of("rrn", uniqueRrn(), "pan", unusedPan(), "amount", 0)))
                .when()
                .post("/api/internal/rollback")
                .then()
                .statusCode(400);
    }

    private String authorizeAndGetRrn(String pan, long amount) {
        String rrn = authorizationApi()
                .body(toJson(internalAuthorizationBody(pan, uniqueStan(), amount)))
                .when()
                .post("/api/internal/authorize")
                .then()
                .statusCode(200)
                .extract()
                .path("rrn");
        assertEquals(12, rrn.length(), "RRN должен содержать 12 цифр");
        return rrn;
    }
}
