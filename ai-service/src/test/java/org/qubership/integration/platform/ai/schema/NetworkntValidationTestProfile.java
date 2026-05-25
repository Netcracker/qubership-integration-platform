package org.qubership.integration.platform.ai.schema;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Enables networknt validation for pilot element types in tests. */
public class NetworkntValidationTestProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "qip.ai.schema.validation.engine",
        "networknt",
        "qip.ai.schema.validation.networknt.element-types",
        "service-call,http-trigger");
  }
}
