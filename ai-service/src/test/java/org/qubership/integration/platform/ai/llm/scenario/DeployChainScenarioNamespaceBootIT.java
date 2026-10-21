package org.qubership.integration.platform.ai.llm.scenario;

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
 * Local and test boots must not require CIP namespace. Create topics still fails later if it stays
 * blank.
 */
@QuarkusTest
@TestProfile(DeployChainScenarioNamespaceBootIT.BlankNamespaceProfile.class)
class DeployChainScenarioNamespaceBootIT {

  public static class BlankNamespaceProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("cloud.microservice.namespace", "", "namespace", "", "NAMESPACE", "");
    }
  }

  @Inject
  @ForScenario(ScenarioType.DEPLOY_CHAIN)
  ScenarioHandler scenario;

  @Inject Config config;

  @Test
  void bootsWhenMicroserviceNamespaceIsBlank() {
    assertNotNull(scenario);
    assertTrue(
        config
            .getOptionalValue("cloud.microservice.namespace", String.class)
            .map(String::isBlank)
            .orElse(true));
  }
}
