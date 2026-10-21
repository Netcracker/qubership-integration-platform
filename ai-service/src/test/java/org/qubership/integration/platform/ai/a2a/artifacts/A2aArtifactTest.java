package org.qubership.integration.platform.ai.a2a.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Artifact;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;

class A2aArtifactTest {

  @Test
  void projectsAllAllowlistedTypesFromDurableEvidence() {
    for (String type : CreateChainPublicArtifactProjector.ALLOWED_TYPES) {
      CreateChainPublicArtifact artifact =
          CreateChainPublicArtifactProjector.project(
                  new CreateChainArtifactEvidence(
                      "id-" + type, type, 5L, "h".repeat(64), Map.of("summary", type + " ok")))
              .orElseThrow();
      assertEquals(type, artifact.type());
      assertEquals(5L, artifact.revision());
      assertEquals("h".repeat(64), artifact.contentHash());
      // Reviewable content is embedded. An unresolved app:// pointer is a forbidden key now, so
      // emitting one would make the projector throw rather than produce this artifact.
      assertEquals(type + " ok", artifact.payload().get("summary"));
      assertFalse(artifact.payload().containsKey("contentRef"));
      Artifact sdk = CreateChainA2aArtifactMapper.toSdkArtifact(artifact);
      assertEquals(artifact.id(), sdk.artifactId());
      assertEquals(type, sdk.name());
      assertEquals(type, sdk.metadata().get("type"));
      assertEquals(5L, ((Number) sdk.metadata().get("revision")).longValue());
    }
  }

  @Test
  void artifactReadyEventsProjectIdempotentlyByIdAndRevision() {
    List<CreateChainEvent> events =
        List.of(
            new CreateChainEvent.ArtifactReady(
                CreateChainPublicArtifactTypes.REQUIREMENT_DRAFT, "draft-1", "a".repeat(64), 1L),
            new CreateChainEvent.ArtifactReady(
                CreateChainPublicArtifactTypes.REQUIREMENT_DRAFT, "draft-1", "a".repeat(64), 1L),
            new CreateChainEvent.ArtifactReady(
                CreateChainPublicArtifactTypes.INTEGRATION_DESIGN, "design-1", "b".repeat(64), 2L));

    List<CreateChainPublicArtifact> first = CreateChainA2aStateMapper.projectArtifacts(events);
    List<CreateChainPublicArtifact> second = CreateChainA2aStateMapper.projectArtifacts(events);

    assertEquals(2, first.size());
    assertEquals(first, second);

    List<CreateChainPublicArtifact> merged =
        CreateChainA2aArtifactMapper.mergeIdempotent(first, first);
    assertEquals(2, merged.size());
    assertTrue(CreateChainA2aArtifactMapper.newlyCommitted(first, first).isEmpty());
    assertEquals(
        1,
        CreateChainA2aArtifactMapper.newlyCommitted(
                first,
                List.of(
                    new CreateChainPublicArtifact(
                        "design-1",
                        CreateChainPublicArtifactTypes.INTEGRATION_DESIGN,
                        3L,
                        "c".repeat(64),
                        Map.of("id", "design-1", "type", "integration-design", "revision", 3L))))
            .size());
  }

  /**
   * Every public type is allowlisted, so the drop is only reachable through a type that resolves to
   * no public kind at all — an internal artifact name reaching the transport by mistake.
   */
  @Test
  void nonAllowlistedArtifactReadyIsDropped() {
    List<CreateChainPublicArtifact> projected =
        CreateChainA2aStateMapper.projectArtifacts(
            List.of(
                new CreateChainEvent.ArtifactReady(
                    "model-trace", "trace-1", "d".repeat(64), 1L)));
    assertTrue(projected.isEmpty());
  }

  @Test
  void duplicateProjectionDoesNotAppendAnotherArtifact() {
    CreateChainPublicArtifact artifact =
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "mat-1",
                    CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
                    9L,
                    "e".repeat(64),
                    Map.of("chainId", "chain-1")))
            .orElseThrow();
    List<CreateChainPublicArtifact> once =
        CreateChainA2aArtifactMapper.mergeIdempotent(List.of(), List.of(artifact));
    List<CreateChainPublicArtifact> twice =
        CreateChainA2aArtifactMapper.mergeIdempotent(once, List.of(artifact));
    assertEquals(1, once.size());
    assertEquals(1, twice.size());
    assertEquals(artifact.revisionKey(), twice.get(0).revisionKey());
    assertFalse(CreateChainA2aArtifactMapper.toSdkArtifacts(twice).isEmpty());
  }
}
