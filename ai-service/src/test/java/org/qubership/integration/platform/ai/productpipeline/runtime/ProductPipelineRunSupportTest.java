package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ProductPipelineRunSupportTest {

  private static final Instant FIXED = Instant.parse("2026-08-25T00:00:00Z");

  @Test
  void rebuildsOnlyConsecutiveFailedAttemptsForEachStage() {
    List<StageAttempt> attempts =
        List.of(
            attempt("analysis", 1, StageStatus.FAILED),
            attempt("planning", 2, StageStatus.FAILED),
            attempt("analysis", 3, StageStatus.SUCCEEDED),
            attempt("planning", 4, StageStatus.FAILED),
            attempt("analysis", 5, StageStatus.FAILED),
            nonTechnicalAttempt("analysis", 6));

    assertEquals(
        Map.of("run-1:planning", 2, "run-1:analysis", 1),
        ProductPipelineRunSupport.consecutiveTechnicalRetries("run-1", attempts));
  }

  @Test
  void restoreForExternalWorkflowVerifiesAvailablePinWithoutLoadingANewerContract()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    ProductPipelineRunStore runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    ProductPipelineArtifactStore artifactStore = new ProductPipelineArtifactStore(artifacts);
    ProductPipelineProfile profile;
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      profile = ProductPipelineProfileParser.parse(in);
    }

    CompilerRunPinResolver pinResolver = mock(CompilerRunPinResolver.class);
    doNothing().when(pinResolver).verifyAvailable(any());
    when(pinResolver.resolve(any(ProductPipelineProfile.class), any(KnowledgeQueryContext.class)))
        .thenThrow(new IllegalStateException("must not resolve a new pin on restore"));
    when(pinResolver.resolve(any(), any(ChainSemanticRevision.class), any(CompilerContract.class)))
        .thenThrow(new IllegalStateException("must not resolve a new pin on restore"));
    doThrow(new IllegalStateException("must not load a newer compiler contract on restore"))
        .when(pinResolver)
        .verifyPersistedPin(any(), any(), any(CompilerContract.class));

    ProductPipelineRunSupport support =
        ProductPipelineRunSupport.builder(
                runStore, artifactStore, new StageCapabilityRegistry(List.of()), clock)
            .profileCatalog(new ProductPipelineProfileCatalog(List.of(profile)))
            .compilerRunPinResolver(pinResolver)
            .build();

    RunManifest manifest = restoreManifest(profile);
    StartOrResumeCommand command =
        new StartOrResumeCommand("conv-restore-pin", "run-restore-pin", profile, manifest);
    support.bootstrap(command, "flow-restore-pin");
    support
        .restoreForExternalWorkflow(command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    verify(pinResolver, org.mockito.Mockito.atLeastOnce()).verifyAvailable(any());
    verify(pinResolver, never())
        .resolve(any(ProductPipelineProfile.class), any(KnowledgeQueryContext.class));
    verify(pinResolver, never())
        .resolve(any(), any(ChainSemanticRevision.class), any(CompilerContract.class));
    verify(pinResolver, never()).verifyPersistedPin(any(), any(), any(CompilerContract.class));
  }

  private static RunManifest restoreManifest(ProductPipelineProfile profile) {
    return new RunManifest(
        "run-restore-pin",
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        new CompilerRunPin(
            "compiler",
            "1",
            "digest",
            2,
            "1",
            "catalog-hash",
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null));
  }

  private static StageAttempt attempt(String stageId, long revision, StageStatus outcome) {
    return new StageAttempt(
        "attempt-" + revision, stageId, revision, outcome, FIXED, FIXED, List.of(), null);
  }

  private static StageAttempt nonTechnicalAttempt(String stageId, long revision) {
    return new StageAttempt(
        "attempt-" + revision,
        stageId,
        revision,
        StageStatus.FAILED,
        FIXED,
        FIXED,
        List.of(),
        ProductPipelineRunSupport.nonTechnicalFailureEvidence("invalid contract"));
  }
}
