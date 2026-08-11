package org.qubership.integration.platform.ai.a2a.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;

class A2aPublicArtifactProjectionTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("allowlistedTypes")
  void projectsAllowlistedTypesDeterministically(String type) {
    CreateChainArtifactEvidence evidence =
        new CreateChainArtifactEvidence(
            "art-" + type,
            type,
            3L,
            "a".repeat(64),
            Map.of("summary", "Safe summary for " + type, "bucket", "secret-bucket"));

    CreateChainPublicArtifact first =
        CreateChainPublicArtifactProjector.project(evidence).orElseThrow();
    CreateChainPublicArtifact second =
        CreateChainPublicArtifactProjector.project(evidence).orElseThrow();

    assertEquals(first, second);
    assertEquals("art-" + type, first.id());
    assertEquals(type, first.type());
    assertEquals(3L, first.revision());
    assertEquals("a".repeat(64), first.contentHash());
    assertFalse(first.payload().containsKey("contentRef"));
    assertFalse(String.valueOf(first.payload()).contains("app://"));
    assertEquals("Safe summary for " + type, first.payload().get("summary"));
    assertFalse(first.payload().containsKey("bucket"));
    assertFalse(first.payload().toString().contains("secret-bucket"));
  }

  static Stream<String> allowlistedTypes() {
    return CreateChainPublicArtifactProjector.ALLOWED_TYPES.stream().sorted();
  }

  @Test
  void mapsWireIdsDocumentToIntegrationDesign() {
    CreateChainPublicArtifact artifact =
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "ids-1",
                    "ids-document",
                    2L,
                    "b".repeat(64),
                    Map.of("title", "CRM sync design")))
            .orElseThrow();
    assertEquals(CreateChainPublicArtifactTypes.INTEGRATION_DESIGN, artifact.type());
    assertEquals("CRM sync design", artifact.payload().get("title"));
  }

  @Test
  void projectsRequirementBriefAsAllowlistedType() {
    CreateChainPublicArtifact artifact =
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "brief-1",
                    "requirement-brief",
                    1L,
                    "c".repeat(64),
                    Map.of("summary", "Need greetings API", "goal", "Expose HTTP greetings")))
            .orElseThrow();
    assertEquals(CreateChainPublicArtifactTypes.REQUIREMENT_BRIEF, artifact.type());
    assertEquals("Need greetings API", artifact.payload().get("summary"));
    assertEquals("Expose HTTP greetings", artifact.payload().get("goal"));
  }

  @Test
  void dropsNonAllowlistedTypes() {
    Optional<CreateChainPublicArtifact> projected =
        CreateChainPublicArtifactProjector.project(
            new CreateChainArtifactEvidence(
                "internal-1",
                "compiler-internal-graph",
                1L,
                "c".repeat(64),
                Map.of("summary", "brief")));
    assertTrue(projected.isEmpty());
  }

  @Test
  void doesNotInventArtifactWithoutEvidence() {
    assertTrue(
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "missing",
                    CreateChainPublicArtifactTypes.FAILURE_REPORT,
                    1L,
                    "d".repeat(64),
                    Map.of()))
            .isPresent());
    // Absent evidence is modeled as Optional.empty() at the call site; projector still requires
    // identity fields and never fabricates an id/hash.
    assertEquals(
        "missing",
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "missing",
                    CreateChainPublicArtifactTypes.FAILURE_REPORT,
                    1L,
                    "d".repeat(64),
                    Map.of()))
            .orElseThrow()
            .id());
  }

  @Test
  void stripsForbiddenInternalFieldsFromPayload() {
    Map<String, Object> dirty = new LinkedHashMap<>();
    dirty.put("summary", "ok");
    dirty.put("bucket", "pipelines");
    dirty.put("objectKey", "compiler-artifacts/x");
    dirty.put("prompt", "system: do not leak");
    dirty.put("modelTrace", Map.of("tokens", 12));
    dirty.put("credentials", "secret");
    dirty.put("rawLog", "stack");
    dirty.put("pipelineSnapshot", Map.of("status", "RUNNING"));
    dirty.put("reference", "Reference[kind=IDS_DOCUMENT, artifactId=x]");

    CreateChainPublicArtifact artifact =
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "safe-1",
                    CreateChainPublicArtifactTypes.REQUIREMENT_DRAFT,
                    1L,
                    "e".repeat(64),
                    dirty))
            .orElseThrow();

    String serialized = artifact.payload().toString();
    assertFalse(serialized.contains("pipelines"));
    assertFalse(serialized.contains("compiler-artifacts"));
    assertFalse(serialized.contains("system:"));
    assertFalse(serialized.contains("secret"));
    assertFalse(serialized.contains("RUNNING"));
    assertFalse(serialized.contains("Reference["));
    assertEquals("ok", artifact.payload().get("summary"));
  }
}
