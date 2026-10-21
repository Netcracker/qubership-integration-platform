package org.qubership.integration.platform.ai.harness.rest;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class PublicApiBoundaryTest {
  @ParameterizedTest
  @ValueSource(strings = {"skill-run", "planner-run", "executor-run", "chain-patch-run"})
  void harnessRoutesAreDisabledByDefault(String route) {
    given().contentType("application/json").body("{}")
        .when().post("/api/v1/harness/" + route)
        .then().statusCode(404);
  }

  @ParameterizedTest
  @ValueSource(strings = {"conversation-archive/Y2hhdA/source.json", "product-pipeline-runs/run.json"})
  void internalObjectsCannotBeDownloadedThroughThePublicEndpoint(String key) {
    given().queryParam("key", key)
        .when().get("/api/v1/storage/objects")
        .then().statusCode(404);
  }
}
