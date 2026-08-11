package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;

/**
 * Proves Docker/jar classpath packaging for product-pipeline startup resources. Local filesystem
 * fallbacks are unavailable inside the Compose image, so these resources must ship in the jar.
 */
class ProductPipelineClasspathResourcesTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @Test
  void artifactSchemasArePackagedOnClasspath() throws Exception {
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/artifact-schemas.yaml")) {
      assertNotNull(in, "artifact-schemas.yaml must be on the ai-service classpath");
      @SuppressWarnings("unchecked")
      Map<String, Object> root = YAML.readValue(in, Map.class);
      Object types = root.get("types");
      assertTrue(types instanceof List<?> list && !list.isEmpty(), "types list must be non-empty");
    }
  }

  @Test
  void createProfilesArePackagedOnClasspath() throws Exception {
    try (InputStream chain =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml");
        InputStream chainV2 =
            getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      assertTrue(
          getClass().getResourceAsStream("/product-pipelines/profiles/create-plan-v1.yaml") == null,
          "create-plan-v1.yaml must be absent after hard cutover");
      assertNotNull(chain, "create-chain-v1.yaml must be on the ai-service classpath");
      assertNotNull(chainV2, "create-chain-v2.yaml must be on the ai-service classpath");
      ProductPipelineProfile createChain = ProductPipelineProfileParser.parse(chain);
      ProductPipelineProfile createChainV2 = ProductPipelineProfileParser.parse(chainV2);
      assertFalse(createChain.stages().isEmpty());
      assertFalse(createChainV2.stages().isEmpty());
      assertEquals("1", createChain.profileVersion());
      assertEquals("2", createChainV2.profileVersion());
    }
  }
}
