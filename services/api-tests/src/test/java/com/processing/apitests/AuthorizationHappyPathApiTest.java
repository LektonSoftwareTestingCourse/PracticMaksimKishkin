package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Позитивный сценарий авторизации через Gateway — эталон для негативных.
 * Источник ожиданий — docs/api-spec.md, POST /api/transactions.
 */
@DisplayName("Authorization: позитивный сценарий")
class AuthorizationHappyPathApiTest extends ApiTestBase {

    @Test
    @DisplayName("POST /api/transactions -> 200 APPROVED, rrn 12 цифр, authCode 6 символов")
    void approveValidTransaction() {
        String pan = createCard();
        String stan = uniqueStan();

        gateway()
                .body(toJson(authorizationBody(pan, stan, 150_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("stan", equalTo(stan))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("^\\d{12}$"))
                .body("authCode", matchesPattern("^[A-Z0-9]{6}$"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    // DEF-01: код возвращает эхо mti "0100", спецификация декларирует "0110".
    @Test
    @DisplayName("Фактический mti ответа — эхо запроса (DEF-01)")
    void approvedResponseMtiIsEchoOfRequest() {
        String pan = createCard();

        gateway()
                .body(toJson(authorizationBody(pan, uniqueStan(), 10_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("mti", equalTo("0100"));
    }

    @Test
    @DisplayName("Повторная авторизация на другой карте не зависит от предыдущих тестов")
    void secondApprovalUsesItsOwnCard() {
        String firstPan = createCard();
        String secondPan = createCard();
        String firstStan = uniqueStan();
        String secondStan = uniqueStan();

        gateway().body(toJson(authorizationBody(firstPan, firstStan, 5_000L)))
                .when().post("/api/transactions").then().statusCode(200);
        gateway()
                .body(toJson(authorizationBody(secondPan, secondStan, 7_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("stan", equalTo(secondStan))
                .body("status", equalTo("APPROVED"));
    }

    @Test
    @DisplayName("Успешная авторизация возвращает заполненные системные поля")
    void approvalContainsGeneratedValues() {
        String pan = createCard();

        Map<String, Object> response = gateway()
                .body(toJson(authorizationBody(pan, uniqueStan(), 1_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("$");

        assertNotNull(response.get("rrn"), "rrn должен быть заполнен");
        assertNotNull(response.get("authCode"), "authCode должен быть заполнен");
    }
}
