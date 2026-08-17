package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexBuilder;
import org.qubership.integration.platform.ai.plan.RequirementTopologyGuard;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFactsService;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.CompilerPipelinePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.mockito.ArgumentCaptor;

class PlanningCapabilityTest {

  @Test
  void executeStopsAtPlanCandidatesWithoutBundlePublication() {
    RequirementBrief brief =
        new RequirementBrief(
            "Greetings",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "script greeting",
            "draft",
            RequirementFactFixtures.GREETINGS_PROMPT,
            RequirementFactFixtures.greetingsApprovedDraft().facts());
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("greetings", "Greetings"),
            List.of(
                new ChainPlanNode(
                    "t1",
                    "http-trigger",
                    "HTTP",
                    null,
                    null,
                    List.of(
                        new PlanProperty("contextPath", "/greetings"),
                        new PlanProperty("httpMethodRestrict", "GET"))),
                new ChainPlanNode(
                    "s1",
                    "script",
                    "Hello",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return \"Hello world!\"")))),
            List.of());

    CompilerPlanningRunner runner =
        CompilerPlanningRunner.forTests(
            new RequirementTopologyGuard(),
            new PlanPresentationFactsService(),
            request ->
                new CompilerPlanningRunner.PlanningSpineOutcome(
                    CompilerPlanningRunner.DEFAULT_SKILL_ORDER,
                    graph,
                    new ValidationResult(true, List.of(), "ok"),
                    "GP-01",
                    "http-trigger -> script",
                    List.of("cip-script-generator")));
    CompilerDerivedPlanningRunner derivedRunner = mock(CompilerDerivedPlanningRunner.class);
    PlanningCapability capability = new PlanningCapability(runner, derivedRunner);

    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("requirementBrief", brief);
    attributes.put("idsBypass", new IdsBypass("skip", "create-plan-v1", "1"));
    attributes.put("expectedSkillOrder", List.copyOf(CompilerPlanningRunner.DEFAULT_SKILL_ORDER));
    StageExecutionContext context =
        new StageExecutionContext(
            "run-1",
            "conv-1",
            "planning",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            Map.copyOf(attributes));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    Multi.createFrom()
        .deferred(() -> capability.execute(context))
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    assertEquals(2, completed.get().outcome().candidates().size());
    assertTrue(
        completed.get().outcome().candidates().stream()
            .anyMatch(
                c ->
                    c.kind()
                        == org.qubership.integration.platform.ai.compiler.artifact
                            .CompilationArtifacts.Kind.IMPLEMENTATION_PLAN));
    verifyNoInteractions(derivedRunner);
  }

  @Test
  void executeUsesDerivedRunnerWhenProfileDeclaresCompilerPipeline() {
    CompilerPlanningRunner legacyRunner = mock(CompilerPlanningRunner.class);
    CompilerDerivedPlanningRunner derivedRunner = mock(CompilerDerivedPlanningRunner.class);
    RequirementBrief brief =
        new RequirementBrief(
            "Greetings",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "draft",
            "Create greetings chain",
            List.of());
    when(derivedRunner.planWithProgress(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(),
                            "derived",
                            null))));
    PlanningCapability capability = new PlanningCapability(legacyRunner, derivedRunner);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-2",
            "conv-2",
            "planning",
            "exec-2",
            "attempt-1",
            profileWithCompilerPipeline(),
            null,
            List.of(),
            Map.of(
                "requirementBrief",
                brief,
                "idsBypass",
                new IdsBypass("skip", "create-chain-v1", "1")));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .collect()
        .asList()
        .await()
        .indefinitely()
        .forEach(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    ArgumentCaptor<CompilerPlanningRequest> requestCaptor =
        ArgumentCaptor.forClass(CompilerPlanningRequest.class);
    verify(derivedRunner).planWithProgress(requestCaptor.capture());
    assertEquals("attempt-1", requestCaptor.getValue().attemptId());
    assertEquals("create-chain-v1", requestCaptor.getValue().idsBypass().profileId());
    verify(legacyRunner, never()).plan(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void createChainProfileWithoutIdsBypassAttributeUsesProfileIdNotCreatePlanLegacy() {
    // Live CreateChainTestOrchestrator hydrates requirementBrief but not idsBypass. Hardcoding
    // create-plan profile removed; product create-chain owns planning.
    CompilerPlanningRunner legacyRunner = mock(CompilerPlanningRunner.class);
    CompilerDerivedPlanningRunner derivedRunner = mock(CompilerDerivedPlanningRunner.class);
    RequirementBrief brief =
        new RequirementBrief(
            "Greetings",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "draft",
            "Create greetings chain",
            List.of());
    when(derivedRunner.planWithProgress(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(),
                            "derived",
                            null))));
    PlanningCapability capability = new PlanningCapability(legacyRunner, derivedRunner);
    ProductPipelineProfile profile = profileWithCompilerPipeline();

    StageExecutionContext context =
        new StageExecutionContext(
            "run-live",
            "conv-live",
            "planning",
            "exec-live",
            "attempt-1",
            profile,
            null,
            List.of(),
            Map.of("requirementBrief", brief));

    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .collect()
        .asList()
        .await()
        .indefinitely()
        .forEach(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    ArgumentCaptor<CompilerPlanningRequest> requestCaptor =
        ArgumentCaptor.forClass(CompilerPlanningRequest.class);
    verify(derivedRunner).planWithProgress(requestCaptor.capture());
    assertEquals("attempt-1", requestCaptor.getValue().attemptId());
    assertEquals(profile.profileId(), requestCaptor.getValue().idsBypass().profileId());
    assertEquals(profile.profileVersion(), requestCaptor.getValue().idsBypass().profileVersion());
    assertTrue(
        !"create-plan-v1".equals(requestCaptor.getValue().idsBypass().profileId()),
        "must not default to create-plan-v1 legacy gate");
    verify(legacyRunner, never()).plan(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void productionCreateChainResolutionIncludesAssemblerAndValidators() {
    CompilerPipelineIndex productionIndex = buildProductionIndex();
    CompilerRunPinResolver resolver = new CompilerRunPinResolver(productionIndex);
    ProductPipelineProfile profile = profileWithCompilerPipeline();
    var knowledgeContext =
        new org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext(
            new org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef(
                "artifact-full@1.0.0",
                "1.0.0",
                "1.0.0",
                "sha256:pinned",
                "CERTIFIED",
                "sha256:certificate"));
    var pin = resolver.resolve(profile, knowledgeContext);
    assertTrue(pin.capabilityClosure().contains("cip-chain-assembler"));
    assertTrue(pin.capabilityClosure().contains("cip-element-validator"));
    assertTrue(pin.capabilityClosure().contains("cip-structural-validator"));
    assertTrue(pin.capabilityClosure().contains("cip-configuration-validator"));
    assertTrue(pin.capabilityClosure().contains("cip-security-validator"));
    assertTrue(pin.capabilityClosure().contains("cip-quality-validator"));
  }

  @Test
  void executeUsesDerivedRunnerForProductionProfileAfterResolutionGate() {
    ProductPipelineProfile profile = profileWithCompilerPipeline();
    CompilerPlanningRunner legacyRunner = mock(CompilerPlanningRunner.class);
    CompilerDerivedPlanningRunner derivedRunner = mock(CompilerDerivedPlanningRunner.class);
    when(derivedRunner.planWithProgress(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(),
                            "derived",
                            null))));
    PlanningCapability capability = new PlanningCapability(legacyRunner, derivedRunner);
    RequirementBrief brief =
        new RequirementBrief(
            "Greetings",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "draft",
            "Create greetings chain",
            List.of());
    StageExecutionContext context =
        new StageExecutionContext(
            "run-prod",
            "conv-prod",
            "planning",
            "exec-prod",
            "attempt-prod",
            profile,
            null,
            List.of(),
            Map.of(
                "requirementBrief",
                brief,
                "idsBypass",
                new IdsBypass("skip", "create-chain-v1", "1")));
    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    capability
        .execute(context)
        .collect()
        .asList()
        .await()
        .indefinitely()
        .forEach(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });

    assertEquals(StageOutcomeClass.CANDIDATE, completed.get().outcome().outcomeClass());
    verify(derivedRunner).planWithProgress(org.mockito.ArgumentMatchers.any());
    verify(legacyRunner, never()).plan(org.mockito.ArgumentMatchers.any());
  }

  private static ProductPipelineProfile profileWithCompilerPipeline() {
    return new ProductPipelineProfile(
        1,
        "create-chain-v1",
        "1",
        List.of(new ArtifactTypeRef("requirement-brief", 1)),
        List.of(),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of(),
        new CompilerPipelinePolicy(
            List.of(2),
            List.of("Discovery", "Planning", "Generation", "Assembly", "Validation"),
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            List.of(new ArtifactTypeRef("compiler-validation-bundle", 1))));
  }

  private static CompilerPipelineIndex buildProductionIndex() {
    try {
      QipKnowledgePackTestSupport.configureAddonPackRoot();
      var policy = QipKnowledgePackTestSupport.buildPolicyFromFixture();
      var packRoot = QipKnowledgePackFixturePaths.packRoot();
      QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
      var result = ingestionService.ingest(packRoot);
      QipKnowledgePackScanResult scanResult =
          new QipKnowledgePackScanResult(packRoot, result.manifest().version(), result.files());
      return new CompilerPipelineIndexBuilder()
          .build(scanResult, policy, QipKnowledgePackFixturePaths.addonRoot());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build production pipeline index", e);
    }
  }
}
