package com.processing.apitests;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Контракт Card Management через Gateway.
 * Источник ожиданий — docs/api-spec.md (раздел 2) и контроллеры сервиса
 */
@DisplayName("Cards API")
class CardApiTest extends ApiTestBase {

    @Test
    @DisplayName("POST /api/cards -> 201, тело по схеме, значения из запроса сохранены")
    void createCardReturnsCreatedCard() {
        Map<String, Object> body = validCardBody();
        gateway()
                .body(toJson(body))
                .when()
                .post("/api/cards")
                .then()
                .statusCode(201)
                .header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("id", notNullValue())
                .body("pan", matchesPattern("^\\d{16}$"))
                .body("bin", equalTo(BIN))
                .body("cardholderName", equalTo("AUTOTEST USER"))
                .body("status", equalTo("ACTIVE"))
                .body("currencyCode", equalTo("643"))
                .body("issuerId", equalTo("ISS001"))
                .body("dailyLimit", equalTo(15000000))
                .body("monthlyLimit", equalTo(300000000))
                .body("availableBalance", equalTo(50000000))
                .body("expiryDate", matchesPattern("^\\d{4}-\\d{2}$"))
                .body("createdAt", notNullValue());
    }

    @Test
    @DisplayName("POST /api/cards с незарегистрированным BIN -> 404 BinNotFoundException")
    void createCardWithUnknownBinReturnsNotFound() {
        Map<String, Object> body = cardBody("999999",
                DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, DEFAULT_BALANCE);
        gateway()
                .body(toJson(body))
                .when()
                .post("/api/cards")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("BinNotFoundException"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString("Unknown BIN"));
    }

    static Stream<Arguments> invalidCardRequests() {
        return Stream.of(
                Arguments.of("bin", "40000", "BIN number must contain exactly 6 digits"),
                Arguments.of("bin", null, "must not be blank"),
                Arguments.of("cardholderName", "ivan ivanov", "Cardholder name"),
                Arguments.of("cardholderName", null, "must not be blank"),
                Arguments.of("currencyCode", "A43", "Value can contain digits only"),
                Arguments.of("currencyCode", "64", "The length must be exactly 3 symbols"),
                Arguments.of("dailyLimit", -1, "Value can not be negative"),
                Arguments.of("monthlyLimit", -1, "Value can not be negative"));
    }

    @ParameterizedTest(name = "{0}={1} -> 400")
    @MethodSource("invalidCardRequests")
    @DisplayName("POST /api/cards: каждое правило валидатора — отдельный класс эквивалентности")
    void createCardRejectsInvalidField(String field, Object value, String expectedMessage) {
        Map<String, Object> body = validCardBody();
        body.put(field, value);

        gateway()
                .body(toJson(body))
                .when()
                .post("/api/cards")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString(expectedMessage));
    }

    @Test
    @DisplayName("POST /api/cards/generate -> 201, generated и массив карт")
    void generateCardsReturnsBatch() {
        gateway()
                .body(toJson(Map.of("count", 1, "bins", List.of("400001"))))
                .when()
                .post("/api/cards/generate")
                .then()
                .statusCode(201)
                .body("generated", equalTo(1))
                .body("cards.size()", equalTo(1))
                .body("cards[0].pan", matchesPattern("^\\d{16}$"));
    }

    @Test
    @DisplayName("POST /api/cards/generate с count=0 -> 400")
    void generateCardsRejectsZeroCount() {
        gateway()
                .body(toJson(Map.of("count", 0, "bins", List.of("400001"))))
                .when()
                .post("/api/cards/generate")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("POST /api/cards/generate с пустым списком BIN -> 400")
    void generateCardsRejectsEmptyBins() {
        gateway()
                .body(toJson(Map.of("count", 1, "bins", List.of())))
                .when()
                .post("/api/cards/generate")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"));
    }

    @Test
    @DisplayName("POST /api/cards/generate выше лимита -> 400 CardGenerationLimitException")
    void generateCardsRejectsTooLargeBatch() {
        gateway()
                .body(toJson(Map.of("count", 10001, "bins", List.of("400001"))))
                .when()
                .post("/api/cards/generate")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardGenerationLimitException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("GET /api/cards/{pan} -> 200 и полная карта")
    void getCardReturnsStoredCard() {
        String pan = createCard();

        gatewayGet()
                .pathParam("pan", pan)
                .when()
                .get("/api/cards/{pan}")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(pan))
                .body("availableBalance", equalTo(50000000));
    }

    @Test
    @DisplayName("GET /api/cards/{pan} с PAN неверного формата -> 400")
    void getCardWithInvalidPanReturnsBadRequest() {
        gatewayGet()
                .pathParam("pan", "123")
                .when()
                .get("/api/cards/{pan}")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("ConstraintViolationException"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString("PAN number must contain exactly 16 digits"));
    }

    @Test
    @DisplayName("GET /api/cards/{pan} для несуществующей карты -> 404")
    void getUnknownCardReturnsNotFound() {
        String pan = unusedPan();

        gatewayGet()
                .pathParam("pan", pan)
                .when()
                .get("/api/cards/{pan}")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString("was not found"));
    }

    @Test
    @DisplayName("GET /api/cards?limit=2 -> 200, размер страницы не превышает limit")
    void listCardsRespectsLimit() {
        createCard();

        gatewayGet()
                .queryParam("limit", 2)
                .queryParam("offset", 0)
                .when()
                .get("/api/cards")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("cards.size()", lessThanOrEqualTo(2));
    }

    @Test
    @DisplayName("GET /api/cards?limit=0 -> 400 (граница: допустимо от 1)")
    void listCardsRejectsZeroLimit() {
        gatewayGet()
                .queryParam("limit", 0)
                .when()
                .get("/api/cards")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("ConstraintViolationException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("GET /api/cards?limit=10001 -> 413 (граница max-page-limit=10000)")
    void listCardsRejectsTooLargeLimit() {
        gatewayGet()
                .queryParam("limit", 10001)
                .when()
                .get("/api/cards")
                .then()
                .statusCode(413)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("TooLargeLimitException"))
                .body("message", containsString("10000"));
    }

    @Test
    @DisplayName("GET /api/cards?offset=-1 -> 400")
    void listCardsRejectsNegativeOffset() {
        gatewayGet()
                .queryParam("offset", -1)
                .when()
                .get("/api/cards")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("PATCH /api/cards/{pan} -> 200, изменённые поля возвращаются")
    void patchCardUpdatesStatusAndBalance() {
        String pan = createCard();
        Map<String, Object> patch = Map.of("status", "BLOCKED", "availableBalance", 12345);

        patchCard(pan, patch)
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(pan))
                .body("status", equalTo("BLOCKED"))
                .body("availableBalance", equalTo(12345));
    }

    @Test
    @DisplayName("PATCH /api/cards/{pan}: дневной лимит больше месячного -> 400")
    void patchCardRejectsDailyLimitAboveMonthlyLimit() {
        String pan = createCard();
        Map<String, Object> patch = Map.of("dailyLimit", 200_000L, "monthlyLimit", 100_000L);

        patchCard(pan, patch)
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("IllegalArgumentException"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString("Daily limit can not be larger than monthly limit"));
    }

    @Test
    @DisplayName("PATCH /api/cards/{pan}: отрицательный лимит -> 400")
    void patchCardRejectsNegativeLimit() {
        String pan = createCard();

        patchCard(pan, Map.of("dailyLimit", -1))
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsString("Value can not be negative"));
    }

    @Test
    @DisplayName("PATCH /api/cards/{pan} несуществующей карты -> 404")
    void patchUnknownCardReturnsNotFound() {
        patchCard(unusedPan(), Map.of("status", "BLOCKED"))
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    @DisplayName("DELETE /api/cards/{pan} -> 204 без тела")
    void deleteCardReturnsNoContent() {
        String pan = createCard();

        Response response = gateway()
                .pathParam("pan", pan)
                .when()
                .delete("/api/cards/{pan}");

        assertEquals(204, response.statusCode(), "Ответ DELETE: " + response.asString());
        assertEquals("", response.asString(), "Тело 204 должно быть пустым");
    }

    @Test
    @DisplayName("DELETE /api/cards/{pan} дважды -> 404: удалённая карта скрыта @SQLRestriction")
    void deleteCardTwiceReturnsNotFound() {
        String pan = createCard();
        assertEquals(204, gateway().pathParam("pan", pan).when().delete("/api/cards/{pan}").statusCode());

        gateway()
                .pathParam("pan", pan)
                .when()
                .delete("/api/cards/{pan}")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("DELETE карты с PAN неверного формата -> 400")
    void deleteCardWithInvalidPanReturnsBadRequest() {
        gateway()
                .pathParam("pan", "123")
                .when()
                .delete("/api/cards/{pan}")
                .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("ConstraintViolationException"));
    }

    @Test
    @DisplayName("DELETE несуществующей карты -> 404")
    void deleteUnknownCardReturnsNotFound() {
        gateway()
                .pathParam("pan", unusedPan())
                .when()
                .delete("/api/cards/{pan}")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"));
    }

    // Удалённая карта скрыта @SQLRestriction("status <> 'DELETED'") на CardEntity.
    @Test
    @DisplayName("GET удалённой карты -> 404 CardNotFoundException")
    void deletedCardIsNotVisible() {
        String pan = createCard();
        assertEquals(204, gateway().pathParam("pan", pan).when().delete("/api/cards/{pan}").statusCode());

        gatewayGet()
                .pathParam("pan", pan)
                .when()
                .get("/api/cards/{pan}")
                .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"))
                .body("message", containsString("was not found"));
    }

    @Test
    @DisplayName("GET /api/cards/{pan}: X-Cache сначала MISS, затем HIT; тела совпадают")
    void cardReadsAreCachedByGateway() {
        String pan = createCard();

        Response first = gatewayGet().pathParam("pan", pan).when().get("/api/cards/{pan}");
        assertEquals(200, first.statusCode());
        assertEquals("MISS", first.header("X-Cache"), "Первый GET должен быть промахом кеша");

        Response second = gatewayGet().pathParam("pan", pan).when().get("/api/cards/{pan}");
        assertEquals(200, second.statusCode());
        assertEquals("HIT", second.header("X-Cache"), "Повторный GET должен попадать в кеш");
        assertEquals(first.asString(), second.asString(), "Кешированный ответ должен совпадать с исходным");
    }

    @Test
    @DisplayName("PATCH карты инвалидирует кеш Gateway: следующий GET снова MISS")
    void writeRequestInvalidatesCardsCache() {
        String pan = createCard();
        assertEquals("MISS", gatewayGet().pathParam("pan", pan).when().get("/api/cards/{pan}").header("X-Cache"));

        patchCard(pan, Map.of("availableBalance", 777)).then().statusCode(200);

        gatewayGet()
                .pathParam("pan", pan)
                .when()
                .get("/api/cards/{pan}")
                .then()
                .statusCode(200)
                .header("X-Cache", equalTo("MISS"))
                .body("availableBalance", equalTo(777))
                .body("status", equalTo("ACTIVE"));
    }
}
