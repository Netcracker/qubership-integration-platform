package org.qubership.integration.platform.ai.a2a;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;

/**
 * Proves default/production-ish boot keeps A2A discovery and invocation unavailable.
 */
@QuarkusTest
@TestProfile(A2aSdkBootDisabledProfile.class)
class A2aSdkBootDisabledCompatibilityTest {

  @Inject A2aFeatureGate featureGate;

  @Test
  void defaultBootKeepsA2aUnavailable() {
    assertFalse(featureGate.enabled(), "qip.ai.a2a.enabled must stay false in this profile");

    given()
        .when()
        .get("/.well-known/agent-card.json")
        .then()
        .statusCode(equalTo(503))
        .body(containsString(A2aFeatureGate.DISABLED_MESSAGE))
        .body(not(containsString(A2aProtocolConstants.AGENT_NAME)));

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/message:send")
        .then()
        .statusCode(equalTo(503))
        .body(containsString(A2aFeatureGate.DISABLED_MESSAGE));

    given().when().get("/q/health").then().statusCode(not(equalTo(404)));
  }
}
