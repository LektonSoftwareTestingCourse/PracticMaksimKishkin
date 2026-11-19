package com.processing.apitests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Базовый класс API-тестов СМП: адреса сервисов, общие спеки и фабрики данных.
 * Источник ожиданий — {@code docs/api-spec.md} и код сервисов
 */
public abstract class ApiTestBase {

    protected static final String GATEWAY = setting("SMP_GATEWAY_URL", "http://localhost:8080");

    protected static final String SWITCH = setting("SMP_SWITCH_URL", "http://localhost:8082");

    protected static final String AUTHORIZATION = setting("SMP_AUTH_URL", "http://localhost:8083");

    protected static final String LOGGER = setting("SMP_LOGGER_URL", "http://localhost:8088");

    protected static final String MERCHANT = setting("SMP_MERCHANT_URL", "http://localhost:8084");

    protected static final String BIN = "400000";

    protected static final String UNUSED_BIN = "400004";

    protected static final long DEFAULT_BALANCE = 50_000_000L;
    protected static final long DEFAULT_DAILY_LIMIT = 15_000_000L;
    protected static final long DEFAULT_MONTHLY_LIMIT = 300_000_000L;

    protected static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeAll
    static void verifyStackIsUp() {
        given()
                .baseUri(GATEWAY)
                .accept(ContentType.JSON)
                .when()
                .get("/health")
                .then()
                .statusCode(200);
    }

    protected static RequestSpecification gateway() {
        return given()
                .baseUri(GATEWAY)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    protected static RequestSpecification gatewayGet() {
        return given()
                .baseUri(GATEWAY)
                .accept(ContentType.JSON);
    }

    protected static RequestSpecification loggerApi() {
        return given()
                .baseUri(LOGGER)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    protected static RequestSpecification switchApi() {
        return given()
                .baseUri(SWITCH)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    protected static RequestSpecification authorizationApi() {
        return given()
                .baseUri(AUTHORIZATION)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    protected static RequestSpecification merchantApi() {
        return given()
                .baseUri(MERCHANT)
                .accept(ContentType.JSON);
    }

    protected static String uniqueStan() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    protected static String uniqueRrn() {
        long value = ThreadLocalRandom.current().nextLong(1_000_000_000_000L);
        return String.format("%012d", value);
    }

    protected static String unusedPan() {
        long value = ThreadLocalRandom.current().nextLong(10_000_000_000L);
        return UNUSED_BIN + String.format("%010d", value);
    }

    protected static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    protected static String toJson(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Не удалось сериализовать тело запроса", exception);
        }
    }

    protected static Map<String, Object> cardBody(String bin,
                                                  Long dailyLimit, Long monthlyLimit, Long initialBalance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bin", bin);
        body.put("cardholderName", "AUTOTEST USER");
        body.put("currencyCode", "643");
        body.put("dailyLimit", dailyLimit);
        body.put("monthlyLimit", monthlyLimit);
        body.put("initialBalance", initialBalance);
        return body;
    }

    protected static Map<String, Object> validCardBody() {
        return cardBody(BIN,
                DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, DEFAULT_BALANCE);
    }

    protected String createCard(long dailyLimit, long monthlyLimit, long balance) {
        Response response = gateway()
                .body(toJson(cardBody(BIN, dailyLimit, monthlyLimit, balance)))
                .when()
                .post("/api/cards");
        assertEquals(201, response.statusCode(),
                "Подготовка данных: карта должна создаваться, ответ: " + response.asString());
        return response.path("pan");
    }

    protected String createCard() {
        return createCard(DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT, DEFAULT_BALANCE);
    }

    protected static Response patchCard(String pan, Map<String, Object> patch) {
        return gateway()
                .body(toJson(patch))
                .when()
                .patch("/api/cards/{pan}", pan);
    }

    protected static Map<String, Object> authorizationBody(String pan, String stan, long amount,
                                                           Instant transmissionDateTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mti", "0100");
        body.put("stan", stan);
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", amount);
        body.put("currencyCode", "643");
        body.put("transmissionDateTime", transmissionDateTime);
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        return body;
    }

    protected static Map<String, Object> authorizationBody(String pan, String stan, long amount) {
        return authorizationBody(pan, stan, amount, Instant.now());
    }

    protected static Map<String, Object> internalAuthorizationBody(String pan, String stan, long amount) {
        Map<String, Object> body = authorizationBody(pan, stan, amount);
        body.put("issuerId", "ISS001");
        return body;
    }

    // Метки времени усечены до миллисекунд: Logger сравнивает Instant точно,
    // а PostgreSQL хранит timestamp(6), поэтому повтор даёт 409 (DEF-11).
    protected static Map<String, Object> loggerTransaction(String id, String pan, String stan, long amount) {
        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return loggerTransaction(id, pan, stan, amount, timestamp, timestamp);
    }

    protected static Map<String, Object> loggerTransaction(String id, String pan, String stan, long amount,
                                                           Instant transmissionDateTime, Instant createdAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("mti", "0100");
        body.put("stan", stan);
        body.put("rrn", uniqueRrn());
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", amount);
        body.put("currencyCode", "643");
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        body.put("acquiringFee", 100);
        body.put("status", "APPROVED");
        body.put("declineReason", null);
        body.put("authCode", "ABC123");
        body.put("processingTimeMs", 10);
        body.put("transmissionDateTime", transmissionDateTime);
        body.put("createdAt", createdAt);
        return body;
    }

    protected static ConditionFactory eventually() {
        return await()
                .atMost(Duration.ofSeconds(15))
                .pollDelay(Duration.ofMillis(500))
                .pollInterval(Duration.ofSeconds(1));
    }

    // Фильтра stan в API нет: ищем по pan+dateFrom, отбор — GPath-фильтром.
    protected static void awaitTransactionVisible(String pan, String stan) {
        eventually().untilAsserted(() -> gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .body("transactions.findAll { it.stan == '" + stan + "' }.size()", greaterThanOrEqualTo(1)));
    }

    protected static Map<String, Object> findStoredTransaction(String pan, String stan) {
        return gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .extract()
                .path("transactions.findAll { it.stan == '" + stan + "' }[0]");
    }

    protected static int storedCount(String pan, String stan) {
        Response response = gatewayGet()
                .queryParam("pan", pan)
                .queryParam("dateFrom", today())
                .when()
                .get("/api/transactions/search")
                .then()
                .statusCode(200)
                .extract()
                .response();
        return response.path("transactions.findAll { it.stan == '" + stan + "' }.size()");
    }

    private static String setting(String environmentVariable, String defaultValue) {
        String systemProperty = System.getProperty(environmentVariable.toLowerCase().replace('_', '.'));
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }
        String environmentValue = System.getenv(environmentVariable);
        return environmentValue != null && !environmentValue.isBlank() ? environmentValue : defaultValue;
    }
}
