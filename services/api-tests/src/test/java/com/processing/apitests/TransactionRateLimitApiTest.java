package com.processing.apitests;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лимит Gateway 100 запросов/с на IP: 429, Retry-After, retryAfterMs.
 * Изоляция — синтетический IP в X-Forwarded-For (ClientIpResolver).
 */
@DisplayName("Gateway: rate limit на POST /api/transactions")
class TransactionRateLimitApiTest extends ApiTestBase {

    private static final int ATTEMPTS = 200;
    private static final int THREADS = 32;

    @Test
    @DisplayName("Burst > capacity -> 429 с Retry-After и RATE_LIMIT_EXCEEDED")
    void exceedingRateLimitReturnsTooManyRequests() throws Exception {
        String clientIp = "198.51.100." + ThreadLocalRandom.current().nextInt(2, 250);

        List<Response> responses = sendBurst(clientIp);
        Response limited = responses.stream()
                .filter(response -> response.statusCode() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Ожидался хотя бы один 429; коды ответов: " + statusCodes(responses)));

        assertEquals("1", limited.header("Retry-After"), "Retry-After должен сообщать паузу в секундах");
        limited.then()
                .body("error", equalTo("RATE_LIMIT_EXCEEDED"))
                .body("message", containsString("Too many requests"))
                .body("retryAfterMs", equalTo(1000));

        assertTrue(responses.stream().anyMatch(response -> response.statusCode() == 400),
                "Запросы в пределах лимита должны доходить до валидатора: " + statusCodes(responses));
    }

    @Test
    @DisplayName("Лимит считается по клиентскому IP: другой IP не получает 429")
    void rateLimitIsIsolatedPerClientIp() throws Exception {
        String exhaustedIp = "203.0.113." + ThreadLocalRandom.current().nextInt(2, 250);
        sendBurst(exhaustedIp);

        String otherIp = "192.0.2." + ThreadLocalRandom.current().nextInt(2, 250);
        Response other = gateway()
                .header("X-Forwarded-For", otherIp)
                .body("{}")
                .when()
                .post("/api/transactions");

        assertEquals(400, other.statusCode(),
                "Для другого IP ожидается ошибка валидации, а не 429: " + other.asString());
    }

    private List<Response> sendBurst(String clientIp) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < ATTEMPTS; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return gateway()
                            .header("X-Forwarded-For", clientIp)
                            .header("Connection", "close")
                            .body("{}")
                            .when()
                            .post("/api/transactions");
                }));
            }
            start.countDown();

            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                try {
                    responses.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException | TimeoutException ignored) {
                    // Отброшен: под burst Gateway рвёт часть keep-alive соединений.
                }
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Integer> statusCodes(List<Response> responses) {
        return responses.stream().map(Response::statusCode).distinct().toList();
    }
}
