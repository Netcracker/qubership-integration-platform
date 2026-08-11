package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Uni;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFactsService;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class CompilerDerivedPlanningRunnerTest {

  @Test
  void wiredRunnerBuildsCandidatesFromSpineWorkspace() {
    InMemorySkillWorkspaceStore workspaceStore =
        new InMemorySkillWorkspaceStore(new ChainPlanStore());
    ChainPlanGraph graph = graph();
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, "digest-1", List.of(), List.of(), List.of());
    CompilerValidationBundle bundle = new CompilerValidationBundle(1, "digest-1", List.of());
    workspaceStore.putArtifact(
        "conv-1",
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "cip-chain-assembler",
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    workspaceStore.putArtifact(
        "conv-1",
        SkillArtifact.of(
            SkillArtifactType.GRAPH_ASSEMBLY_RESULT,
            "cip-chain-assembler",
            new SkillArtifactPayload.GraphAssemblyResultPayload(assembly)));
    workspaceStore.putArtifact(
        "conv-1",
        SkillArtifact.of(
            SkillArtifactType.COMPILER_VALIDATION_BUNDLE,
            "cip-element-validator",
            new SkillArtifactPayload.CompilerValidationBundlePayload(bundle)));
    workspaceStore.putArtifact(
        "conv-1",
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "planning-seed",
            new SkillArtifactPayload.RawUserRequestPayload("Create sales flow", List.of())));

    CompilerDerivedPlanningSpine spine = mock(CompilerDerivedPlanningSpine.class);
    when(spine.execute(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new CompilerPlanningRunner.PlanningSpineOutcome(
                        List.of("cip-chain-assembler"),
                        graph,
                        new ValidationResult(true, List.of(), "ok"),
                        "GP-01",
                        "http-trigger",
                        List.of())));
    CompilerPlanValidator planValidator = mock(CompilerPlanValidator.class);
    when(planValidator.validate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));

    CompilerDerivedPlanningRunner runner =
        new CompilerDerivedPlanningRunner(
            spine, workspaceStore, new PlanPresentationFactsService(), planValidator);

    StageOutcome outcome = runner.plan(request()).await().indefinitely();
    Set<Kind> kinds = new HashSet<>();
    for (var candidate : outcome.candidates()) {
      kinds.add(candidate.kind());
    }

    assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass());
    assertEquals(
        Set.of(
            Kind.IMPLEMENTATION_PLAN,
            Kind.PLAN_VALIDATION_RESULT,
            Kind.CHAIN_PLAN_GRAPH,
            Kind.GRAPH_ASSEMBLY_RESULT,
            Kind.COMPILER_VALIDATION_BUNDLE),
        kinds);
  }

  @Test
  void candidateContainsEveryMaterializationInput() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) ->
                new CompilerDerivedPlanningRunner.DerivedPlanningResult(
                    implementationPlan(),
                    new PlanValidationResult(List.of()),
                    graph(),
                    new GraphAssemblyResult(1, graph(), "digest-1", List.of(), List.of(), List.of()),
                    new CompilerValidationBundle(1, "digest-1", List.of()),
                    List.of("cip-chain-assembler")));

    StageOutcome outcome = runner.plan(request()).await().indefinitely();
    Set<Kind> kinds = new HashSet<>();
    for (var candidate : outcome.candidates()) {
      kinds.add(candidate.kind());
    }

    assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass());
    assertEquals(
        Set.of(
            Kind.IMPLEMENTATION_PLAN,
            Kind.PLAN_VALIDATION_RESULT,
            Kind.CHAIN_PLAN_GRAPH,
            Kind.GRAPH_ASSEMBLY_RESULT,
            Kind.COMPILER_VALIDATION_BUNDLE),
        kinds);
  }

  @Test
  void planValidationBlockerPreventsApprovalEligibility() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) ->
                new CompilerDerivedPlanningRunner.DerivedPlanningResult(
                    implementationPlan(),
                    new PlanValidationResult(
                        List.of(
                            new PlanValidationFinding(
                                "PLAN_BLOCKER", "Plan contains blocker", true))),
                    graph(),
                    new GraphAssemblyResult(1, graph(), "digest-1", List.of(), List.of(), List.of()),
                    new CompilerValidationBundle(1, "digest-1", List.of()),
                    List.of("cip-chain-assembler")));

    StageOutcome outcome = runner.plan(request()).await().indefinitely();

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, outcome.outcomeClass());
    assertFalse(
        ((PlanValidationResult) outcome.candidates().get(0).payload()).approvalEligible());
    assertTrue(outcome.message().contains("PLAN_BLOCKER"));
    assertTrue(outcome.message().contains("Plan contains blocker"));
  }

  @Test
  void formatValidationFailureMessageIncludesBlockerFindings() {
    String message =
        CompilerDerivedPlanningRunner.formatValidationFailureMessage(
            new PlanValidationResult(
                List.of(
                    new PlanValidationFinding("A", "first blocker", true),
                    new PlanValidationFinding("B", "warning only", false),
                    new PlanValidationFinding("C", "second blocker", true))));
    assertTrue(message.startsWith("planning validation failed"));
    assertTrue(message.contains("A: first blocker"));
    assertTrue(message.contains("C: second blocker"));
    assertFalse(message.contains("warning only"));
  }

  @Test
  void mergeCompilerBundleFindingsSurfacesElementBlockers() {
    var issue =
        new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue(
            "element-1",
            org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity
                .BLOCKER,
            "Element properties violate schema for 'http-trigger'",
            "cip-element-validator",
            List.of("http-trigger-1"),
            List.of(),
            "Fix node properties according to schema");
    var invalidPass =
        new org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass(
            "cip-element-validator",
            new ValidationResult(false, List.of(issue), "element validation failed with 1 blocker(s)"));
    var bundle = new CompilerValidationBundle(1, "digest-1", List.of(invalidPass));

    PlanValidationResult merged =
        CompilerDerivedPlanningRunner.mergeCompilerBundleFindings(
            new PlanValidationResult(List.of()), bundle);

    assertFalse(merged.approvalEligible());
    assertTrue(
        merged.findings().stream()
            .anyMatch(f -> f.message() != null && f.message().contains("http-trigger")));
  }

  @Test
  void missingCompilerOrPlanValidationFailsClosed() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) ->
                new CompilerDerivedPlanningRunner.DerivedPlanningResult(
                    implementationPlan(),
                    null,
                    graph(),
                    new GraphAssemblyResult(1, graph(), "digest-1", List.of(), List.of(), List.of()),
                    null,
                    List.of("cip-chain-assembler")));

    StageOutcome outcome = runner.plan(request()).await().indefinitely();

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("required"));
  }

  @Test
  void planWithProgressEmitsSkillStepsBeforeCompletion() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) -> {
              progress.accept("cip-pattern-selector", "running");
              progress.accept("cip-pattern-selector", "completed");
              progress.accept("cip-chain-generator", "running");
              progress.accept("cip-chain-generator", "completed");
              return new CompilerDerivedPlanningRunner.DerivedPlanningResult(
                  implementationPlan(),
                  new PlanValidationResult(List.of()),
                  graph(),
                  new GraphAssemblyResult(1, graph(), "digest-1", List.of(), List.of(), List.of()),
                  new CompilerValidationBundle(1, "digest-1", List.of()),
                  List.of("cip-pattern-selector", "cip-chain-generator"));
            });

    List<CapabilitySignal> signals =
        runner.planWithProgress(request()).collect().asList().await().indefinitely();

    assertEquals(5, signals.size());
    assertEquals(
        new CapabilitySignal.SkillProgress("cip-pattern-selector", "running"), signals.get(0));
    assertEquals(
        new CapabilitySignal.SkillProgress("cip-pattern-selector", "completed"), signals.get(1));
    assertEquals(
        new CapabilitySignal.SkillProgress("cip-chain-generator", "running"), signals.get(2));
    assertEquals(
        new CapabilitySignal.SkillProgress("cip-chain-generator", "completed"), signals.get(3));
    assertTrue(signals.get(4) instanceof CapabilitySignal.Completed);
  }

  @Test
  void planWithProgressCompletesWithRetryableOutcomeWhenSkillArtifactsAreMissing() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) -> {
              progress.accept("cip-structure-generator", "error");
              throw new PlanningSkillArtifactUnavailableException(
                  "cip-structure-generator",
                  Set.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                  new IllegalStateException("status=FAILED"));
            });

    List<CapabilitySignal> signals =
        runner.planWithProgress(request()).collect().asList().await().indefinitely();

    assertTrue(
        signals.stream().anyMatch(
            signal ->
                signal instanceof CapabilitySignal.SkillProgress progress
                    && progress.skillId().equals("cip-structure-generator")
                    && progress.status().equals("error")));
    CapabilitySignal.Completed completed =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(
        StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
        completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().contains("CHAIN_STRUCTURE"));
    assertTrue(
        completed.outcome().message().contains(
            "downstream generators were not started"));
  }

  @Test
  void invocationKeyConflictCompletesAsContractFailure() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) -> {
              throw new IllegalStateException(
                  "contract failure: GRAPH_PATCH_ARTIFACT invocationKey conflict for 'abc'");
            });
    StageOutcome outcome = runner.plan(request()).await().indefinitely();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("invocationKey conflict"));
  }

  @Test
  void planWithProgressMapsInvocationKeyConflictToCompletedContractFailure() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) -> {
              throw new IllegalStateException(
                  "contract failure: GRAPH_PATCH_ARTIFACT invocationKey conflict for 'abc'");
            });

    List<CapabilitySignal> signals =
        runner.planWithProgress(request()).collect().asList().await().indefinitely();

    CapabilitySignal.Completed completed =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().contains("invocationKey conflict"));
    // Multi must complete (not fail): collect().asList() returns without exception.
  }

  @Test
  void planCompletesWithRetryableOutcomeWhenSkillArtifactsAreMissing() {
    CompilerDerivedPlanningRunner runner =
        CompilerDerivedPlanningRunner.forTests(
            (request, progress) -> {
              throw new PlanningSkillArtifactUnavailableException(
                  "cip-structure-generator",
                  Set.of(SkillArtifactType.CHAIN_STRUCTURE.name()),
                  new IllegalStateException("status=FAILED"));
            });

    StageOutcome outcome =
        runner.plan(request()).await().indefinitely();

    assertEquals(
        StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
        outcome.outcomeClass());
    assertTrue(outcome.message().contains("CHAIN_STRUCTURE"));
    assertTrue(
        outcome.message().contains(
            "downstream generators were not started"));
  }

  private static CompilerPlanningRequest request() {
    return new CompilerPlanningRequest(
        "conv-1",
        "run-1",
        new RequirementBrief(
            "SalesFlow",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            "draft",
            "Create sales flow",
            List.of()),
        new IdsBypass("skip", "create-chain-v1", "1"),
        "24.4",
        List.of(),
        List.of(),
        null);
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("sales", "Sales"),
        List.of(
            new ChainPlanNode(
                "trigger",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(new PlanProperty("contextPath", "/sales")))),
        List.of());
  }

  private static ImplementationPlan implementationPlan() {
    return ImplementationPlan.schemaVersion2(
        "Plan text",
        "planning-capability",
        "1",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
