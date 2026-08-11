package org.qubership.integration.platform.ai.a2a;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Production-ish profile: A2A no-op boot producers stay off.
 */
public class A2aSdkBootDisabledProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qip.ai.a2a.enabled", "false");
  }
}
