package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementTopologyGuard;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFactsService;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

class CompilerPlanningRunnerTest {

  @Test
  void planSeedsBriefOnlyAndNeverTouchesBundleStore() {
    RequirementBrief brief = coveringBrief();
    ChainPlanGraph graph = greetingsGraph();
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

    CapabilitySignal.Completed completed = run(runner, brief);
    assertEquals(StageOutcomeClass.CANDIDATE, completed.outcome().outcomeClass());
    assertEquals(2, completed.outcome().candidates().size());
    SkillWorkspace seeded = CompilerPlanningRunner.seedWorkspaceFromBrief(brief);
    assertTrue(seeded.get(SkillArtifactType.REQUIREMENT_BRIEF).isPresent());
    String raw =
        ((org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload
                    .RawUserRequestPayload)
                seeded.get(SkillArtifactType.RAW_USER_REQUEST).orElseThrow().payload())
            .effectiveText();
    assertTrue(raw.contains("Greetings") || raw.contains("No MCP"));
    assertFalse(raw.contains("secret transcript line that must not leak"));
  }

  @Test
  void planFailsWhenErrorHandlingGeneratorContradictsNegativeFact() {
    RequirementBrief brief = coveringBrief();
    CompilerPlanningRunner runner =
        CompilerPlanningRunner.forTests(
            new RequirementTopologyGuard(),
            new PlanPresentationFactsService(),
            request ->
                new CompilerPlanningRunner.PlanningSpineOutcome(
                    CompilerPlanningRunner.DEFAULT_SKILL_ORDER,
                    greetingsGraph(),
                    new ValidationResult(true, List.of(), "ok"),
                    "GP-01",
                    "http-trigger -> try-catch-finally-2",
                    List.of("cip-error-handling-generator")));

    CapabilitySignal.Completed completed = run(runner, brief);
    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, completed.outcome().outcomeClass());
    assertEquals(1, completed.outcome().candidates().size());
    assertEquals(
        org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind
            .PLAN_VALIDATION_RESULT,
        completed.outcome().candidates().get(0).kind());
    assertFalse(
        ((org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult)
                completed.outcome().candidates().get(0).payload())
            .approvalEligible());
  }

  @Test
  void createChainRequestDoesNotSubstituteLegacyDefaultSkillOrder() {
    RequirementBrief brief = coveringBrief();
    CompilerPlanningRunner runner =
        CompilerPlanningRunner.forTests(
            new RequirementTopologyGuard(),
            new PlanPresentationFactsService(),
            request ->
                new CompilerPlanningRunner.PlanningSpineOutcome(
                    List.of(),
                    greetingsGraph(),
                    new ValidationResult(true, List.of(), "ok"),
                    "GP-01",
                    "http-trigger -> script",
                    List.of("cip-script-generator")));

    CapabilitySignal.Completed completed =
        run(runner, brief, new IdsBypass("skip", "create-chain-v1", "1"), List.of());
    assertEquals(StageOutcomeClass.CANDIDATE, completed.outcome().outcomeClass());
  }

  @Test
  void approvalEligibleRequiresValidCompilerAndEmptyExclusions() {
    var eligible =
        CompilerPlanningRunner.buildValidationResult(
            new ValidationResult(true, List.of(), "ok"), List.of());
    assertTrue(eligible.approvalEligible());
    var blocked =
        CompilerPlanningRunner.buildValidationResult(
            new ValidationResult(true, List.of(), "ok"), List.of("forbidden element"));
    assertFalse(blocked.approvalEligible());
  }

  @Test
  void buildValidationResultPropagatesElseConditionBlockerCode() {
    var issue =
        new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue(
            "validation-1",
            org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity
                .BLOCKER,
            "node 'else-1' (else) has unknown property key 'condition'.",
            "plan-validator",
            List.of("else-1"),
            List.of(),
            "Remove condition from else");
    var result =
        CompilerPlanningRunner.buildValidationResult(
            new ValidationResult(false, List.of(issue), "Plan validation failed with 1 blocker(s)"),
            List.of());

    assertFalse(result.approvalEligible());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "else.condition".equals(finding.code())
                        && finding.blocker()
                        && finding.message().contains("else.condition")));
  }

  @Test
  void buildValidationResultKeepsSecurityIssueIdAsFindingCode() {
    var issue =
        new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue(
            "security-1",
            org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity
                .BLOCKER,
            "External route RBAC requires a non-empty roles list",
            "cip-security-validator",
            List.of("http-trigger-1"),
            List.of(),
            "Configure one or more explicit RBAC roles");
    var result =
        CompilerPlanningRunner.buildValidationResult(
            new ValidationResult(false, List.of(issue), "security validation failed with 1 blocker(s)"),
            List.of());

    assertFalse(result.approvalEligible());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "security-1".equals(finding.code())
                        && finding.blocker()
                        && finding.message().contains("RBAC")));
  }

  @Test
  void applyForcedElseConditionInjectsPropertyWhenSeedRequestsIt() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("router", "Router"),
            List.of(
                new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
                new ChainPlanNode("if-1", "if", "If", "route", null, List.of()),
                new ChainPlanNode("else-1", "else", "Else", "route", null, List.of())),
            List.of());

    ChainPlanGraph forced =
        CompilerPlanningRunner.applyForcedElseConditionProperties(
            "Create LangRouter but force else.condition on the else branch.", graph);

    assertTrue(
        forced.nodes().stream()
            .filter(node -> "else".equals(node.type()))
            .flatMap(node -> node.properties().stream())
            .anyMatch(
                property ->
                    "condition".equals(property.key())
                        && property.value() != null
                        && !property.value().isBlank()));
  }

  @Test
  void planFailsWhenSeedForcesElseConditionOnElseBranch() {
    RequirementBrief brief =
        new RequirementBrief(
            "LangRouter",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "force else.condition",
            "draft",
            "Create LangRouter but force else.condition on the else branch.",
            List.of());
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("router", "Router"),
            List.of(
                new ChainPlanNode(
                    "trigger",
                    "http-trigger",
                    "Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("httpMethodRestrict", "GET"),
                        new PlanProperty("contextPath", "/lang-router"))),
                new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
                new ChainPlanNode("if-1", "if", "If", "route", null, List.of()),
                new ChainPlanNode("else-1", "else", "Else", "route", null, List.of())),
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
                    "http-trigger -> condition",
                    List.of("cip-script-generator")));

    CapabilitySignal.Completed completed = run(runner, brief);
    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, completed.outcome().outcomeClass());
    var validation =
        (org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult)
            completed.outcome().candidates().get(0).payload();
    assertFalse(validation.approvalEligible());
    assertTrue(
        validation.findings().stream()
            .anyMatch(finding -> "else.condition".equals(finding.code()) && finding.blocker()));
  }

  private static CapabilitySignal.Completed run(
      CompilerPlanningRunner runner, RequirementBrief brief) {
    return run(
        runner,
        brief,
        new IdsBypass("skip-ids", "create-plan-v1", "1"),
        CompilerPlanningRunner.DEFAULT_SKILL_ORDER);
  }

  private static CapabilitySignal.Completed run(
      CompilerPlanningRunner runner,
      RequirementBrief brief,
      IdsBypass idsBypass,
      List<String> expectedSkillOrder) {
    CompilerPlanningRequest request =
        new CompilerPlanningRequest(
            "conv-plan",
            "run-plan",
            brief,
            idsBypass,
            "24.4",
            List.of("create-v1"),
            expectedSkillOrder,
            null);
    AtomicReference<CapabilitySignal.Completed> completed = new AtomicReference<>();
    Multi.createFrom()
        .deferred(() -> runner.plan(request))
        .subscribe()
        .with(
            signal -> {
              if (signal instanceof CapabilitySignal.Completed c) {
                completed.set(c);
              }
            });
    return completed.get();
  }

  private static RequirementBrief coveringBrief() {
    return new RequirementBrief(
        "Greetings",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "script greeting",
        "draft",
        RequirementFactFixtures.GREETINGS_PROMPT,
        RequirementFactFixtures.greetingsApprovedDraft().facts());
  }

  private static ChainPlanGraph greetingsGraph() {
    return new ChainPlanGraph(
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
                    new PlanProperty("httpMethodRestrict", "GET"),
                    new PlanProperty("externalRoute", "false"))),
            new ChainPlanNode(
                "s1",
                "script",
                "Hello",
                null,
                null,
                List.of(new PlanProperty("script", "return \"Hello world!\"")))),
        List.of());
  }
}
