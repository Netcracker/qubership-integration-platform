package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineCompatibilityAnalyzer;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.UnsupportedCreateRunBindingException;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.pack.ClasspathCipKnowledgePackRepository;

@ExtendWith(MockitoExtension.class)
class ProductPipelineRuntimeProducersTest {

  private final ProductPipelineRuntimeProducers producers = new ProductPipelineRuntimeProducers();

  @Mock private CipKnowledgePackRepository pack;

  @ParameterizedTest
  @ValueSource(strings = {"design-planning", "planning"})
  void shouldKeepSupportedProfilesWhenPackIncludesRetiredCreate(String retiredPlanningCapability) throws Exception {
    String current = resource("/product-pipelines/profiles/create-chain-v2.yaml");
    String retired = current.replace("profileVersion: \"2\"", "profileVersion: \"1\"")
        .replace("capabilityId: design-planning", "capabilityId: " + retiredPlanningCapability);
    String override = current.replace("maxTechnicalRetries: 2", "maxTechnicalRetries: 3");
    Map<String, String> resources = Map.of(
        "profiles/retired.yaml", retired,
        "profiles/current.yaml", override,
        "profiles/future.yaml", current.replace("profileVersion: \"2\"", "profileVersion: \"3\""));
    when(pack.loadProductPipelineProfileResources()).thenReturn(
        List.of("profiles/retired.yaml", "profiles/current.yaml", "profiles/future.yaml"));
    when(pack.openProductPipelineResource(anyString())).thenAnswer(call -> {
      String content = resources.get(call.getArgument(0, String.class));
      return content == null ? null : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    });
    var fixture = new ClasspathCipKnowledgePackRepository(new CipKnowledgePackVersion("integration-platform-skills", "integration-platform-skills"));
    var index = fixture.loadCompilerPipelineIndex();
    when(pack.loadCompilerPipelineIndex()).thenReturn(index);
    when(pack.loadPipelineCompatibilityReport()).thenReturn(
        new CompilerPipelineCompatibilityAnalyzer().compare(null, index));

    var catalog = producers.profileCatalog(pack);

    assertAll(
        () -> assertFalse(catalog.supports("create-chain", "1")),
        () -> assertEquals(3, catalog.require("create-chain", "2").stages().getFirst().retry().maxTechnicalRetries()),
        () -> assertEquals("3", catalog.require("create-chain", "3").profileVersion()));
    String conversationId = "mixed-pack";
    String runId = conversationId + "-create-chain-1";
    byte[] persisted = resource("/product-pipelines/historical-create-chain-v1-binding.json")
        .formatted(conversationId, runId, runId).getBytes(StandardCharsets.UTF_8);
    String key = "product-pipeline-create-bindings/" + conversationId + ".json";
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    blobs.put(key, persisted);
    var before = blobs.getVersioned(key).orElseThrow();
    var bindings = new CreateRunBindingStore(blobs, new ObjectMapper(), catalog);

    var failure = assertThrows(UnsupportedCreateRunBindingException.class, () -> bindings.load(conversationId));

    var after = blobs.getVersioned(key).orElseThrow();
    assertAll(
        () -> assertEquals(UnsupportedCreateRunBindingException.ERROR_ID, failure.errorId()),
        () -> assertEquals(before.version(), after.version()),
        () -> assertArrayEquals(persisted, after.content()));
  }

  private static String resource(String path) throws java.io.IOException {
    try (var in = ProductPipelineRuntimeProducersTest.class.getResourceAsStream(path)) {
      assertNotNull(in, path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
