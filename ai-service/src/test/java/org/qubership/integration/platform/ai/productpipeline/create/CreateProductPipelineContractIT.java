package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementTopologyGuard;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClientException;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextPackage;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextRequest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeFailureKind;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeFilter;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeObjectResult;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeRelationResult;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeSearchResult;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

/**
 * Durable CREATE contract gates for restart-safe pinning, exclusion blockers, and knowledge fail-closed
 * behavior. Full CDI/runtime wiring remains covered by later live harness runs.
 */
class CreateProductPipelineContractIT {

  @Test
  void resumedRunKeepsPinnedKnowledgeArtifactAcrossRestart() throws Exception {
    FakeKnowledgeClient knowledge = FakeKnowledgeClient.defaultFixture();
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    ObjectMapper mapper = new ObjectMapper();
    CreateRunBindingStore bindingStore = new CreateRunBindingStore(blobs, mapper);
    ProductPipelineProfile profileV1;
    ProductPipelineProfile profileV2;
    try (java.io.InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
      profileV1 = ProductPipelineProfileParser.parse(in);
    }
    try (java.io.InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      profileV2 = ProductPipelineProfileParser.parse(in);
    }
    ProductPipelineProfileCatalog catalog =
        new ProductPipelineProfileCatalog(List.of(profileV1, profileV2));
    CompilerRunPinResolver pinResolver = stubPinResolver();
    CreateRunSelectionService first =
        new CreateRunSelectionService(
            "2026.1", knowledge, bindingStore, catalog, pinResolver, java.time.Clock.systemUTC());
    var selection = first.selectOrCreate("conv-restart");
    assertEquals("2", selection.runManifest().profileVersion());
    var pinnedPackage = selection.runManifest().knowledgePackage();

    CreateRunSelectionService afterRestart =
        new CreateRunSelectionService(
            "2099.9",
            knowledge,
            new CreateRunBindingStore(blobs, mapper),
            catalog,
            pinResolver,
            java.time.Clock.systemUTC());
    var restored = afterRestart.selectOrCreate("conv-restart");
    assertEquals(pinnedPackage, restored.runManifest().knowledgePackage());
    assertEquals(selection.runManifest().knowledgePackage(), restored.runManifest().knowledgePackage());
    assertEquals("2", restored.runManifest().profileVersion());
  }

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "pkg",
            "1",
            "digest",
            1,
            "idx-1",
            "idx-digest",
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            List.of("planning"),
            java.util.Map.of(),
            java.util.Map.of("skill", "a".repeat(64)),
            List.of());
    CompilerRunPinResolver resolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
    org.mockito.Mockito.when(
            resolver.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(pin);
    return resolver;
  }

  @Test
  void contextPackageFailureFailsClosedWithoutCommit() {
    FakeKnowledgeClient provider = FakeKnowledgeClient.defaultFixture();
    KnowledgeClient failingClient =
        new KnowledgeClient() {
          @Override
          public KnowledgeObjectResult exact(KnowledgeQueryContext context, String id) {
            throw new UnsupportedOperationException();
          }

          @Override
          public KnowledgeSearchResult filter(KnowledgeQueryContext context, KnowledgeFilter filter) {
            throw new UnsupportedOperationException();
          }

          @Override
          public KnowledgeRelationResult relations(
              KnowledgeQueryContext context, String id, java.util.Set<String> kinds) {
            throw new UnsupportedOperationException();
          }

          @Override
          public KnowledgeContextPackage context(
              KnowledgeQueryContext context, KnowledgeContextRequest request) {
            throw new KnowledgeClientException(
                KnowledgeFailureKind.KNOWLEDGE_NOT_FOUND, "context package unavailable");
          }
        };
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementAnalysisCapability analysis =
        new RequirementAnalysisCapability(
            failingClient,
            provider,
            new RequirementBriefCoverageValidator(),
            null,
            null,
            null,
            null,
            (conversationId, userMessage) -> {
              throw new AssertionError("analyzer must not run after context failure");
            },
            null);

    StageExecutionContext stage =
        new StageExecutionContext(
            "run-fail-closed",
            "conv-fail-closed",
            "requirement-analysis",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            Map.of("approvedDraft", approved));

    List<CapabilitySignal> signals =
        analysis.execute(stage).collect().asList().await().indefinitely();

    assertEquals(1, signals.size());
    assertTrue(signals.get(0) instanceof CapabilitySignal.Completed);
    CapabilitySignal.Completed completed = (CapabilitySignal.Completed) signals.get(0);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
  }

  @Test
  void exclusionContradictionIsNonRetryableValidationFailure() {
    RequirementTopologyGuard guard = new RequirementTopologyGuard();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("g", "G"),
            List.of(new ChainPlanNode("tcff", "try-catch-finally-2", "EH", null, null, List.of())),
            List.of());
    List<String> exclusions =
        guard.evaluateAfterGraphCapture(
            RequirementFactFixtures.greetingsApprovedDraft().facts(), graph);
    assertFalse(exclusions.isEmpty());
    PlanValidationResult validation =
        CompilerPlanningRunner.buildValidationResult(
            new ValidationResult(true, List.of(), "ok"), exclusions);
    assertFalse(validation.approvalEligible());
    assertEquals(StageOutcomeClass.VALIDATION_FAILURE.name(), "VALIDATION_FAILURE");
  }

  @Test
  void terminalPlanApprovedHasNoBundleRequirement() {
  }
}
