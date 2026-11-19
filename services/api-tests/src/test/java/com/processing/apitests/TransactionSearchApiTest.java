package com.processing.apitests;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт GET /api/transactions/search и /export (docs/api-spec.md, раздел 3).
 * Фильтры проверяются попарно: pan+status, pan+mcc, pan+issuerId,
 * pan+declineReason.
 */
@DisplayName("Transactions: поиск и экспорт")
class TransactionSearchApiTest extends ApiTestBase {

    @Test
    @DisplayName("Поиск по pan возвращает только транзакции этой карты")
    void searchFiltersByPan() {
        String pan = createCard();
        String stan = postApproved(pan, 1_000L);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions.pan", everyItem(equalTo(pan)))
                .body("transactions.findAll { it.stan == '" + stan + "' }.size()", equalTo(1));
    }

    @Test
    @DisplayName("Пустая выборка -> 200 total=0 (а не 404)")
    void searchWithNoMatchesReturnsEmptyPage() {
        gatewayGet()
                .queryParam("pan", unusedPan())
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions.size()", equalTo(0));
    }

    @Test
    @DisplayName("Фильтр status=APPROVED возвращает только APPROVED")
    void searchFiltersByStatus() {
        String pan = createCard();
        postApproved(pan, 1_000L);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("status", "APPROVED")
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions.status", everyItem(equalTo("APPROVED")));
    }

    @Test
    @DisplayName("Фильтр status=DECLINED находит отклонённую транзакцию")
    void searchFiltersByDeclinedStatus() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, 1_000L);
        String stan = uniqueStan();
        gateway().body(toJson(authorizationBody(pan, stan, 5_000L)))
                .when().post("/api/transactions").then().statusCode(200)
                .body("status", equalTo("DECLINED"));
        awaitTransactionVisible(pan, stan);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("status", "DECLINED")
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions.status", everyItem(equalTo("DECLINED")));
    }

    @Test
    @DisplayName("Фильтр mcc возвращает только указанную категорию")
    void searchFiltersByMcc() {
        String pan = createCard();
        String stan = uniqueStan();
        Map<String, Object> body = authorizationBody(pan, stan, 1_000L);
        body.put("mcc", "5999");
        gateway().body(toJson(body)).when().post("/api/transactions").then().statusCode(200);
        awaitTransactionVisible(pan, stan);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("mcc", "5999")
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions.mcc", everyItem(equalTo("5999")));
    }

    // DEF-06: фильтра issuerId нет в спецификации, но он реализован в
    // TransactionFilter.
    @Test
    @DisplayName("Фильтр issuerId (вне спецификации) находит транзакции эмитента ISS001")
    void searchFiltersByIssuerId() {
        String pan = createCard();
        String stan = postApproved(pan, 1_000L);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("issuerId", "ISS001")
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions.findAll { it.stan == '" + stan + "' }.size()", equalTo(1))
                .body("transactions.issuerId", everyItem(equalTo("ISS001")));
    }

    @Test
    @DisplayName("declineReason — полнотекстовый поиск, а не точное совпадение")
    void searchFiltersByDeclineReasonFullText() {
        String pan = createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, 1_000L);
        String stan = uniqueStan();
        gateway().body(toJson(authorizationBody(pan, stan, 9_000L)))
                .when().post("/api/transactions").then().statusCode(200)
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
        awaitTransactionVisible(pan, stan);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("declineReason", "INSUFFICIENT")
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions.declineReason", everyItem(containsString("INSUFFICIENT")));
    }

    @Test
    @DisplayName("dateFrom/dateTo ограничивают выборку текущими сутками")
    void searchFiltersByDateRange() {
        String pan = createCard();
        String stan = postApproved(pan, 1_000L);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .queryParam("dateTo", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("transactions.findAll { it.stan == '" + stan + "' }.size()", equalTo(1));
    }

    @Test
    @DisplayName("limit=1&offset=0 -> ровно одна запись, total считает все")
    void paginationLimitReturnsSingleRecord() {
        String pan = createCard();
        postApproved(pan, 1_000L);
        postApproved(pan, 2_000L);
        postApproved(pan, 3_000L);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .queryParam("limit", 1)
                .queryParam("offset", 0)
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(3))
                .body("transactions.size()", equalTo(1));
    }

    @Test
    @DisplayName("offset=1 сдвигает выборку: возвращается limit записей")
    void paginationOffsetSkipsRecords() {
        String pan = createCard();
        postApproved(pan, 1_000L);
        postApproved(pan, 2_000L);
        postApproved(pan, 3_000L);

        gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .queryParam("limit", 2)
                .queryParam("offset", 1)
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("transactions.size()", equalTo(2));
    }

    @Test
    @DisplayName("limit=500 — верхняя допустимая граница")
    void paginationAcceptsMaximumLimit() {
        gatewayGet()
                .queryParam("limit", 500)
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("limit=501 -> 400 (граница max=500)")
    void paginationRejectsLimitAboveMaximum() {
        gatewayGet()
                .queryParam("limit", 501)
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @Test
    @DisplayName("limit=0 -> 400 (граница min=1)")
    void paginationRejectsZeroLimit() {
        gatewayGet()
                .queryParam("limit", 0)
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @Test
    @DisplayName("offset=-1 -> 400 (граница min=0)")
    void paginationRejectsNegativeOffset() {
        gatewayGet()
                .queryParam("offset", -1)
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("offset"));
    }

    @Test
    @DisplayName("dateFrom позже dateTo -> 400 (некорректная комбинация параметров)")
    void searchRejectsInvertedDateRange() {
        // Нарушение на уровне класса TransactionFilter: field errors отсутствуют.
        gatewayGet()
                .queryParam("dateFrom", "2026-06-02")
                .queryParam("dateTo", "2026-06-01")
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    @DisplayName("CSV-экспорт: Content-Disposition, text/csv и строка заголовка")
    void exportReturnsCsvAttachmentWithHeaders() {
        String pan = createCard();
        postApproved(pan, 1_000L);

        Response response = gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/export");

        assertEquals(200, response.statusCode());
        assertEquals("attachment; filename=\"transactions.csv\"", response.header("Content-Disposition"));
        assertTrue(response.header("Content-Type").contains("text/csv"),
                "Content-Type: " + response.header("Content-Type"));
        assertTrue(response.asString().startsWith("id,mti,stan,rrn,pan"),
                "CSV должен начинаться со строки заголовка");
        assertTrue(response.asString().contains(pan), "CSV должен содержать PAN отфильтрованной карты");
    }

    @Test
    @DisplayName("CSV-экспорт с невалидным limit -> 400")
    void exportRejectsInvalidLimit() {
        gatewayGet()
                .queryParam("limit", 0)
                .when()
                .get("/api/transactions/export")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    private String postApproved(String pan, long amount) {
        String stan = uniqueStan();
        gateway()
                .body(toJson(authorizationBody(pan, stan, amount)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));
        awaitTransactionVisible(pan, stan);
        return stan;
    }
}
