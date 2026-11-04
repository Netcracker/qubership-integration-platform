package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * {@code cloud.microservice.namespace} defaults to {@code local} when env {@code NAMESPACE} is unset.
 * An empty {@code NAMESPACE} fails boot with {@code SRCFG00040}. MaaS topic creation uses config key
 * {@code namespace} only; blank there fails later with {@code topicNamespaceMissing}.
 */
@QuarkusTest
@TestProfile(DeployChainScenarioNamespaceBootIT.BlankNamespaceProfile.class)
class DeployChainScenarioNamespaceBootIT {

  public static class BlankNamespaceProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("namespace", "");
    }
  }

  @Inject
  @ForScenario(ScenarioType.DEPLOY_CHAIN)
  ScenarioHandler scenario;

  @Inject Config config;

  @Test
  void bootsWhenHelmNamespaceIsBlank() {
    assertNotNull(scenario);
    assertEquals("local", config.getValue("cloud.microservice.namespace", String.class));
    assertTrue(
        config
            .getOptionalValue("namespace", String.class)
            .map(String::isBlank)
            .orElse(true));
  }
}
