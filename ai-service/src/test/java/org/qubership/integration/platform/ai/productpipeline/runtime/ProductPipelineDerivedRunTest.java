package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class ProductPipelineDerivedRunTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T12:30:00Z");

  private ProductPipelineRuntime runtime;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runtime =
        new ProductPipelineRuntime(
            new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC)),
            artifactStore,
            new StageCapabilityRegistry(
                List.of(FakeStageCapabilities.collector(), FakeStageCapabilities.finisher())),
            Clock.fixed(FIXED, ZoneOffset.UTC));
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/two-stage-approval-v1.yaml")) {
      profile = ProductPipelineProfileParser.parse(in);
    }
  }

  @Test
  void derivedRunCopiesPinsAndParentReferences() {
    RunManifest parentManifest = sampleManifest("parent-run");
    runtime
        .startOrResume(
            new StartOrResumeCommand("conv-parent", "parent-run", profile, parentManifest))
        .collect()
        .asList()
        .await()
        .indefinitely();

    CompilationArtifacts.Reference source =
        artifactStore
            .latest("parent-run", CompilationArtifacts.Kind.RUN_MANIFEST)
            .orElseThrow()
            .reference();

    runtime
        .derive(
            new DeriveRunCommand(
                "parent-run",
                "child-run",
                "conv-child",
                profile,
                sampleManifest("child-run"),
                List.of(source)))
        .collect()
        .asList()
        .await()
        .indefinitely();

    RunManifest child =
        artifactStore
            .payload(
                artifactStore
                    .latest("child-run", CompilationArtifacts.Kind.RUN_MANIFEST)
                    .orElseThrow(),
                RunManifest.class);

    assertEquals("parent-run", child.parentRunId());
    assertEquals(List.of(source), child.sourceReferences());
    assertEquals(parentManifest.profileDigest(), child.profileDigest());
    assertEquals(parentManifest.knowledgePackage(), child.knowledgePackage());
    assertEquals(parentManifest.languageVersion(), child.languageVersion());
    assertEquals(parentManifest.dependencyClosureDigest(), child.dependencyClosureDigest());
    assertTrue(
        runtime
            .startOrResume(
                new StartOrResumeCommand("conv-parent", "parent-run", profile, parentManifest))
            .collect()
            .asList()
            .await()
            .indefinitely()
            .stream()
            .anyMatch(PipelineSignal.WaitingForInput.class::isInstance));
  }

  private RunManifest sampleManifest(String runId) {
    return new RunManifest(
        runId,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha-fixed",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("fake-collector", "1", "c1")),
        "closure-sha-fixed",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }
}
