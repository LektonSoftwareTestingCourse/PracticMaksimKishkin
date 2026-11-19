package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Контракт Dashboard API (docs/api-spec.md, раздел 5; DashboardController).
 * Параметры limit и granularity проверяются на границах, пустая выборка — не 404.
 */
@DisplayName("Dashboard API")
class DashboardApiTest extends ApiTestBase {

    @Test
    @DisplayName("GET /api/dashboard/stats -> 200, счётчики и approvalRate в допустимых границах")
    void statsReturnAggregates() {
        gatewayGet()
                .when()
                .get("/api/dashboard/stats")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/dashboard-stats.json"))
                .body("totalTransactions", greaterThanOrEqualTo(0))
                .body("approvedCount", greaterThanOrEqualTo(0))
                .body("declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", greaterThanOrEqualTo(0.0f));
    }

    @Test
    @DisplayName("GET /api/dashboard/recent по умолчанию отдаёт не больше 20 записей")
    void recentUsesDefaultLimit() {
        gatewayGet()
                .when()
                .get("/api/dashboard/recent")
                .then()
                .statusCode(200)
                .body("size()", lessThanOrEqualTo(20));
    }

    @Test
    @DisplayName("GET /api/dashboard/recent?limit=1 -> не больше одной записи")
    void recentRespectsLimit() {
        createCard();

        gatewayGet()
                .queryParam("limit", 1)
                .when()
                .get("/api/dashboard/recent")
                .then()
                .statusCode(200)
                .body("size()", lessThanOrEqualTo(1));
    }

    @Test
    @DisplayName("GET /api/dashboard/recent?limit=0 -> 400")
    void recentRejectsZeroLimit() {
        gatewayGet()
                .queryParam("limit", 0)
                .when()
                .get("/api/dashboard/recent")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Validation error"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @Test
    @DisplayName("GET /api/dashboard/recent?limit=501 -> 400")
    void recentRejectsTooLargeLimit() {
        gatewayGet()
                .queryParam("limit", 501)
                .when()
                .get("/api/dashboard/recent")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("message", containsString("limit"));
    }

    @Test
    @DisplayName("GET /api/dashboard/charts -> 200, массив корзин")
    void chartsReturnBuckets() {
        gatewayGet()
                .when()
                .get("/api/dashboard/charts")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("GET /api/dashboard/charts?granularity=day -> 200")
    void chartsAcceptDayGranularity() {
        gatewayGet()
                .queryParam("granularity", "day")
                .when()
                .get("/api/dashboard/charts")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("GET /api/dashboard/charts?granularity=week -> 400")
    void chartsRejectUnknownGranularity() {
        gatewayGet()
                .queryParam("granularity", "week")
                .when()
                .get("/api/dashboard/charts")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Validation error"))
                .body("message", containsString("granularity"));
    }

    @Test
    @DisplayName("GET /api/dashboard/charts с перевёрнутым диапазоном -> 400")
    void chartsRejectInvertedRange() {
        gatewayGet()
                .queryParam("from", "2026-06-02T00:00:00Z")
                .queryParam("to", "2026-06-01T00:00:00Z")
                .when()
                .get("/api/dashboard/charts")
                .then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("serviceName", equalTo("transaction-logger"));
    }
}
