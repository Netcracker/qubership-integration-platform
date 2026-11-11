package org.qubership.integration.platform.ai.a2a;

import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import java.util.Map;

/**
 * Production-ish profile: A2A no-op boot producers stay off.
 */
public class A2aSdkBootDisabledProfile extends TestArtifactBlobStoreProducer.Profile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qip.ai.a2a.enabled", "false", "qip.ai.a2a.public-base-url", "");
  }
}
