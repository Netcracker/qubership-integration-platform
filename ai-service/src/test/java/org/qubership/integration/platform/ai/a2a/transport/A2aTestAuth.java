package org.qubership.integration.platform.ai.a2a.transport;

/** Bearer token sent by A2A integration tests when exercising catalog-backed skills. */
public final class A2aTestAuth {

  public static final String BEARER = "test-a2a-caller-token";
  public static final String AUTHORIZATION_HEADER = "Bearer " + BEARER;

  private A2aTestAuth() {}
}
