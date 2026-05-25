package org.qubership.integration.platform.ai.bootstrap;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Smoke test verifying that the Quarkus application boots and exposes the health endpoint. */
@QuarkusTest
class HealthEndpointTest {

  @Test
  void healthEndpointIsUp() {
    given().when().get("/q/health").then().statusCode(200).body("status", equalTo("UP"));
  }
}
