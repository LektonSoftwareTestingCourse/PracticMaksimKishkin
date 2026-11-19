package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Бизнес-отказы авторизации: для каждого исхода DeclineOutcome проверяются
 * доменный код и причина отказа совместно.
 */
@DisplayName("Authorization: бизнес-отказы")
class AuthorizationDeclineApiTest extends ApiTestBase {

    private enum PanMode {
        CREATED_CARD,
        UNKNOWN_CARD,
        UNKNOWN_BIN
    }

    // Класс эквивалентности: способ подготовки PAN, состояние карты, сумма,
    // ожидаемые code и reason.
    record DeclineCase(
            String title,
            PanMode panMode,
            String cardStatus,
            Long patchedDailyLimit,
            long createDailyLimit,
            long balance,
            long amount,
            Instant transmissionDate,
            String expectedCode,
            String expectedReason) {

        @Override
        public String toString() {
            return String.valueOf(title);
        }
    }

    static Stream<DeclineCase> declineCases() {
        Instant now = Instant.now();
        Instant farFuture = Instant.parse("2035-01-01T00:00:00Z");
        return Stream.of(
                // CARD_NOT_FOUND: карта не создана; BIN зарегистрирован в Switch
                new DeclineCase("карта не найдена", PanMode.UNKNOWN_CARD, null, null,
                        0, 0, 1_000L, now, "14", "CARD_NOT_FOUND"),
                // CARD_NOT_FOUND: неизвестный BIN — отказ формирует Switch, не Authorization
                new DeclineCase("неизвестный BIN", PanMode.UNKNOWN_BIN, null, null,
                        0, 0, 1_000L, now, "14", "CARD_NOT_FOUND"),
                // CARD_BLOCKED / CARD_INACTIVE: один код 05, различаются причиной
                new DeclineCase("карта заблокирована", PanMode.CREATED_CARD, "BLOCKED", null,
                        DEFAULT_DAILY_LIMIT, DEFAULT_BALANCE, 1_000L, now, "05",
                        "CARD_BLOCKED"),
                new DeclineCase("карта неактивна", PanMode.CREATED_CARD, "INACTIVE", null,
                        DEFAULT_DAILY_LIMIT, DEFAULT_BALANCE, 1_000L, now, "05",
                        "CARD_INACTIVE"),
                // CARD_EXPIRED путь 1: состояние карты EXPIRED
                new DeclineCase("карта просрочена по статусу", PanMode.CREATED_CARD, "EXPIRED", null,
                        DEFAULT_DAILY_LIMIT, DEFAULT_BALANCE, 1_000L, now, "54",
                        "CARD_EXPIRED"),
                // CARD_EXPIRED путь 2: дата передачи позже срока действия карты
                new DeclineCase("карта просрочена по дате запроса", PanMode.CREATED_CARD, "ACTIVE",
                        null,
                        DEFAULT_DAILY_LIMIT, DEFAULT_BALANCE, 1_000L, farFuture, "54",
                        "CARD_EXPIRED"),
                // INSUFFICIENT_FUNDS путь 1: баланс уменьшен состоянием карты
                new DeclineCase("недостаток средств (состояние)", PanMode.CREATED_CARD, "ACTIVE", null,
                        DEFAULT_DAILY_LIMIT, 10_000L, 20_000L, now, "51", "INSUFFICIENT_FUNDS"),
                // INSUFFICIENT_FUNDS путь 2: сумма запроса больше баланса
                new DeclineCase("недостаток средств (данные запроса)", PanMode.CREATED_CARD, "ACTIVE",
                        null,
                        DEFAULT_DAILY_LIMIT, DEFAULT_BALANCE, 60_000_000L, now, "51",
                        "INSUFFICIENT_FUNDS"),
                // EXCEEDS_AMOUNT_LIMIT путь 1: дневной лимит уменьшен через PATCH
                new DeclineCase("превышение лимита (состояние)", PanMode.CREATED_CARD, "ACTIVE",
                        100_000L,
                        DEFAULT_DAILY_LIMIT, DEFAULT_BALANCE, 150_000L, now, "61",
                        "EXCEEDS_AMOUNT_LIMIT"),
                // EXCEEDS_AMOUNT_LIMIT путь 2: карта создана с низким лимитом, сумма в запросе
                // больше
                new DeclineCase("превышение лимита (данные запроса)", PanMode.CREATED_CARD, "ACTIVE",
                        null,
                        100_000L, DEFAULT_BALANCE, 150_000L, now, "61",
                        "EXCEEDS_AMOUNT_LIMIT"));
    }

    @ParameterizedTest(name = "{0}: код {8}, причина {9}")
    @MethodSource("declineCases")
    @DisplayName("Бизнес-отказ: code и declineReason проверяются совместно")
    void declineOutcomeIsReportedWithCodeAndReason(DeclineCase testCase) {
        String pan = preparePan(testCase);

        gateway()
                .body(toJson(authorizationBody(pan, uniqueStan(), testCase.amount(),
                        testCase.transmissionDate())))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(testCase.expectedCode()))
                .body("declineReason", equalTo(testCase.expectedReason()))
                .body("rrn", nullValue())
                .body("authCode", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("CARD_EXPIRED достижим и через статус карты, и через дату запроса")
    void expiredOutcomeIsReachableByBothPaths() {
        String expiredCard = createCard();
        patchCard(expiredCard, Map.of("status", "EXPIRED")).then().statusCode(200);
        String activeCard = createCard();

        String byState = gateway()
                .body(toJson(authorizationBody(expiredCard, uniqueStan(), 1_000L)))
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("responseCode", equalTo("54"))
                .extract().path("declineReason");

        String byRequest = gateway()
                .body(toJson(authorizationBody(activeCard, uniqueStan(), 1_000L,
                        Instant.parse("2035-01-01T00:00:00Z"))))
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("responseCode", equalTo("54"))
                .extract().path("declineReason");

        assertEquals("CARD_EXPIRED", byState);
        assertEquals(byState, byRequest, "Оба пути должны давать одинаковую пару code/reason");
    }

    // DEF-02: отказ по неизвестному BIN возвращает фиксированный mti=0110, а отказы
    // Authorization — эхо 0100.
    @Test
    @DisplayName("Отказ по неизвестному BIN возвращает mti=0110 (DEF-02)")
    void unknownBinDeclineUsesFixedMti() {
        String pan = "999999" + String.format("%010d", 1234567890L);

        gateway()
                .body(toJson(authorizationBody(pan, uniqueStan(), 1_000L)))
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(200)
                .body("mti", equalTo("0110"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    private String preparePan(DeclineCase testCase) {
        if (testCase.panMode() == PanMode.UNKNOWN_BIN) {
            return "999999" + String.format("%010d", 1234567890L);
        }
        if (testCase.panMode() == PanMode.UNKNOWN_CARD) {
            return unusedPan();
        }

        String pan = createCard(testCase.createDailyLimit(), DEFAULT_MONTHLY_LIMIT, testCase.balance());
        if (testCase.cardStatus() != null && !"ACTIVE".equals(testCase.cardStatus())) {
            patchCard(pan, Map.of("status", testCase.cardStatus())).then().statusCode(200);
        }
        if (testCase.patchedDailyLimit() != null) {
            patchCard(pan, Map.of("dailyLimit", testCase.patchedDailyLimit())).then().statusCode(200);
        }
        return pan;
    }
}
