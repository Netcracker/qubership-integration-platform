package org.qubership.integration.platform.ai.plan.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.DecisionTrace;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

class PlanPresentationFactsServiceTest {

  private static final String CONVERSATION_ID = "conv-facts-1";

  private PlanPresentationFactsService service;
  private InMemorySkillWorkspaceStore workspaceStore;
  private SkillWorkspace workspace;

  @BeforeEach
  void setUp() {
    service = new PlanPresentationFactsService();
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    workspaceStore =
        new InMemorySkillWorkspaceStore(new org.qubership.integration.platform.ai.plan.ChainPlanStore());
    workspace = workspaceStore.getOrCreate(CONVERSATION_ID);
  }

  @Test
  void buildsFactsForSimpleGreetingsGraph() {
    seedRequest("Create Greetings chain");
    putGraph(simpleGreetingsGraph());

    PlanPresentationFacts facts = service.build(workspace);

    assertEquals("Create Greetings chain", facts.userRequest());
    assertEquals("Greetings", facts.chainName());
    assertEquals(2, facts.nodeCount());
    assertEquals(2, facts.coreFlowNodes().size());
    assertTrue(facts.compilerAdditions().isEmpty());
    assertEquals("captured_not_built", facts.lifecycleStatus());
  }

  @Test
  void separatesCoreFlowFromErrorHandlingWrapper() {
    seedRequest("Create Greetings chain");
    putGraph(greetingsWithErrorHandling());

    PlanPresentationFacts facts = service.build(workspace);

    assertEquals(6, facts.nodeCount());
    assertEquals(2, facts.coreFlowNodes().size());
    assertTrue(
        facts.coreFlowNodes().stream()
            .anyMatch(node -> "http-trigger".equals(node.type())));
    assertTrue(
        facts.coreFlowNodes().stream().anyMatch(node -> "script".equals(node.type())));
    assertFalse(facts.compilerAdditions().isEmpty());
    assertTrue(
        facts.compilerAdditions().getFirst().description().contains("try-catch-finally-2"));
  }

  @Test
  void includesOptionalArtifactsWhenPresent() {
    seedRequest("build chain");
    putGraph(simpleGreetingsGraph());
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.SELECTED_PATTERN,
            "cip-pattern-selector",
            new SkillArtifactPayload.SelectedPatternPayload(
                new SelectedPattern("GP-01", "HTTP API", "reason", null, List.of(), "summary"))));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.DECISION_TRACE,
            "cip-pattern-selector",
            new SkillArtifactPayload.DecisionTracePayload(
                new DecisionTrace(List.of(), List.of(), "No golden pattern"))));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.PRE_BUILD_VALIDATION,
            "plan-validator",
            new SkillArtifactPayload.ValidationResultPayload(
                new ValidationResult(true, List.of(), "Validation passed"))));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.PLAN_CAPTURE_OUTCOME,
            "plan-validator",
            new SkillArtifactPayload.PlanCaptureOutcomePayload(true, "Plan captured")));

    PlanPresentationFacts facts = service.build(workspace);

    assertEquals("GP-01", facts.selectedPatternId());
    assertEquals("No golden pattern", facts.decisionTraceSummary());
    assertEquals(true, facts.validationPassed());
    assertEquals(true, facts.planCaptured());
  }

  @Test
  void formatFallbackSummaryMentionsCoreFlowAndCompilerAdditions() {
    PlanPresentationFacts facts = service.build(workspaceWithGreetingsAndEh());

    String summary = service.formatFallbackSummary(facts);

    assertTrue(summary.contains("Plan captured (not built in the catalog yet)"));
    assertTrue(summary.contains("Core flow:"));
    assertTrue(summary.contains("Compiler additions:"));
    assertTrue(summary.contains("Implement it"));
  }

  @Test
  void formatPlanReviewSummaryOmitsImplementCta() {
    PlanPresentationFacts facts = service.build(workspaceWithGreetingsAndEh());

    String review = service.formatPlanReviewSummary(facts);
    String fallback = service.formatFallbackSummary(facts);

    assertTrue(review.contains("Plan captured (not built in the catalog yet)"));
    assertFalse(review.contains("Implement it"));
    assertTrue(fallback.startsWith(review));
    assertTrue(fallback.contains("Implement it"));
  }

  @Test
  void propertyLessElseIsPresentedWithoutElseConditionToken() {
    seedRequest("LangRouter with preferredLang condition/if/else");
    putGraph(langRouterWithPropertyLessElse());

    PlanPresentationFacts facts = service.build(workspace);

    assertTrue(facts.branchFacts().contains("else"));
    assertFalse(facts.branchFacts().stream().anyMatch(fact -> fact.contains("else.condition")));
    assertFalse(facts.branchFacts().stream().anyMatch(fact -> fact.startsWith("else:")));
  }

  @Test
  void elseConditionPropertyIsPresentedAsElseConditionFact() {
    seedRequest("force else.condition on the else branch");
    putGraph(langRouterWithElseConditionProperty());

    PlanPresentationFacts facts = service.build(workspace);

    assertTrue(
        facts.branchFacts().stream()
            .anyMatch(fact -> fact.startsWith("else.condition=") && fact.contains("preferredLang")));
  }

  @Test
  void scriptOutcomesKeepBothEvenAndOddMinuteLiteralsBeyondLegacyTruncate() {
    seedRequest("even vs odd minute routing");
    putGraph(langRouterWithLongScriptOutcome());

    PlanPresentationFacts facts = service.build(workspace);

    assertEquals(1, facts.scriptOutcomes().size());
    String script = facts.scriptOutcomes().getFirst();
    assertTrue(script.contains("even minute"), script);
    assertTrue(script.contains("odd minute"), script);
    assertTrue(script.length() > 120, "expected full minute script, got length=" + script.length());
  }

  private SkillWorkspace workspaceWithGreetingsAndEh() {
    seedRequest("Greetings");
    putGraph(greetingsWithErrorHandling());
    return workspace;
  }

  private void seedRequest(String text) {
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "seed",
            new SkillArtifactPayload.RawUserRequestPayload(text, List.of())));
  }

  private void putGraph(ChainPlanGraph graph) {
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "cip-chain-generator",
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
  }

  private static ChainPlanGraph simpleGreetingsGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Greetings", "Returns hello"),
        List.of(
            new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("n2", "script", "Script", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
  }

  private static ChainPlanGraph langRouterWithPropertyLessElse() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("LangRouter", "Route by language"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
            new ChainPlanNode(
                "if-ru",
                "if",
                "Russian",
                "route",
                null,
                List.of(new PlanProperty("condition", "preferredLang == 'ru'"))),
            new ChainPlanNode("else-en", "else", "Default English", "route", null, List.of())),
        List.of());
  }

  private static ChainPlanGraph langRouterWithElseConditionProperty() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("LangRouter", "Route by language"),
        List.of(
            new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
            new ChainPlanNode(
                "else-1",
                "else",
                "Else",
                "route",
                null,
                List.of(new PlanProperty("condition", "preferredLang == 'en'")))),
        List.of());
  }

  private static ChainPlanGraph langRouterWithLongScriptOutcome() {
    String script =
        """
        def currentMinute = Calendar.getInstance().get(Calendar.MINUTE) as int
        if (currentMinute % 2 == 0) {
            return 'even minute'
        } else {
            return 'odd minute'
        }
        """
            .stripIndent()
            .trim();
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("LangRouter", "Route by minute"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "script",
                "script",
                "Read minute",
                null,
                null,
                List.of(new PlanProperty("script", script)))),
        List.of());
  }

  private static ChainPlanGraph greetingsWithErrorHandling() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Greetings", "Returns hello"),
        List.of(
            new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "eh-n1-try-catch-finally", "try-catch-finally-2", "Error Handling", null, null, List.of()),
            new ChainPlanNode("eh-n1-try", "try-2", "Try", "eh-n1-try-catch-finally", null, List.of()),
            new ChainPlanNode("n2", "script", "Script", "eh-n1-try", null, List.of()),
            new ChainPlanNode("eh-n1-catch", "catch-2", "Catch", "eh-n1-try-catch-finally", 1, List.of()),
            new ChainPlanNode(
                "eh-n1-catch-script", "script", "Catch Script", "eh-n1-catch", null, List.of())),
        List.of(
            new ChainPlanEdge("eh-n1-edge-trigger-to-try", "n1", "eh-n1-try-catch-finally", null),
            new ChainPlanEdge("eh-n1-edge-try-to-script", "eh-n1-try", "n2", "eh-n1-try"),
            new ChainPlanEdge(
                "eh-n1-edge-catch-to-script", "eh-n1-catch", "eh-n1-catch-script", "eh-n1-catch")));
  }
}
