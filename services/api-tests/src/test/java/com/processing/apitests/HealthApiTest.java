package com.processing.apitests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

/**
 * Контракт GET /health (docs/api-spec.md, раздел 1 Health).
 */
@DisplayName("Health API")
class HealthApiTest extends ApiTestBase {

    @Test
    @DisplayName("GET /health -> 200, формат по схеме, все зависимости ok")
    void healthReturnsOkForAllDependencies() {
        gatewayGet()
                .when()
                .get("/health")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/health.json"))
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("services", hasKey("switch"))
                .body("services", hasKey("authorization"))
                .body("services", hasKey("cardManagement"))
                .body("services", hasKey("logger"))
                .body("services.switch", equalTo("ok"))
                .body("services.authorization", equalTo("ok"))
                .body("services.cardManagement", equalTo("ok"))
                .body("services.logger", equalTo("ok"));
    }

    @Test
    @DisplayName("GET /health сообщает версию Gateway")
    void healthReportsGatewayVersion() {
        gatewayGet()
                .when()
                .get("/health")
                .then()
                .statusCode(200)
                .body("version", equalTo("1.0.0"));
    }
}
