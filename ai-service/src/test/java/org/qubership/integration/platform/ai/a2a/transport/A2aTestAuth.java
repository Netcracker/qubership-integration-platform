package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;

import io.restassured.specification.RequestSpecification;

/** Bearer token sent by A2A integration tests when exercising catalog-backed skills. */
public final class A2aTestAuth {

  public static final String BEARER = "test-a2a-caller-token";
  public static final String AUTHORIZATION_HEADER = "Bearer " + BEARER;

  private A2aTestAuth() {}

  /** RestAssured request with caller Bearer and A2A protocol version. */
  public static RequestSpecification givenA2a() {
    return given()
        .urlEncodingEnabled(false)
        .header("Authorization", AUTHORIZATION_HEADER)
        .header("A2A-Version", "1.0");
  }

  /**
   * RestAssured request with caller Bearer only — for helpers that set {@code A2A-Version}
   * separately (or omit it).
   */
  public static RequestSpecification givenAuthorized() {
    return given().urlEncodingEnabled(false).header("Authorization", AUTHORIZATION_HEADER);
  }
}
