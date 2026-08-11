package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFacts;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationNode;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;

class ImplementationPlanRendererTest {

  @Test
  void langRouterPlanContainsDecisionCriticalFacts() {
    PlanPresentationFacts facts =
        baseFacts(
            "LangRouter",
            List.of("GET", "/lang-router", "internal", "preferredLang"),
            List.of("preferredLang == 'ru'", "else: English greeting"),
            List.of("Russian greeting text", "English greeting text"),
            List.of(),
            RequirementFactFixtures.langRouterApprovedDraft().facts().stream()
                .filter(f -> f.polarity() == RequirementFactPolarity.NEGATIVE)
                .map(RequirementFact::text)
                .toList());

    ImplementationPlan plan =
        ImplementationPlanRenderer.render(
            facts, "render-implementation-plan", "1", List.of("requirement-brief"), List.of("create-v1"));

    assertEquals(ImplementationPlan.SCHEMA_VERSION_2, plan.schemaVersion());
    assertTrue(plan.planText().contains("/lang-router"));
    assertTrue(plan.planText().contains("GET"));
    assertTrue(plan.planText().contains("preferredLang"));
    assertTrue(plan.planText().contains("preferredLang == 'ru'"));
    assertTrue(plan.planText().contains("English greeting"));
    assertTrue(plan.planText().contains("No service calls"));
    assertTrue(plan.planText().contains("No MCP"));
    assertTrue(ImplementationPlanRenderer.verifyCoverage(plan).isEmpty());
  }

  @Test
  void safeInventoryPlanContainsDecisionCriticalFacts() {
    PlanPresentationFacts facts =
        baseFacts(
            "SafeInventory",
            List.of("GET", "/store/inventory", "external"),
            List.of(),
            List.of("inventory response", "corporate error response"),
            List.of("getInventory", "systemId=sys-1", "GET /store/inventory"),
            List.of());

    ImplementationPlan plan =
        ImplementationPlanRenderer.render(
            facts,
            "render-implementation-plan",
            "1",
            List.of("catalog-binding"),
            List.of("try-catch placement"));

    assertTrue(plan.planText().contains("getInventory"));
    assertTrue(plan.planText().contains("GET /store/inventory"));
    assertTrue(plan.planText().contains("systemId=sys-1"));
    assertTrue(plan.planText().contains("inventory response"));
    assertTrue(plan.planText().contains("corporate error response"));
    assertTrue(plan.planText().contains("try-catch placement"));
    assertTrue(ImplementationPlanRenderer.verifyCoverage(plan).isEmpty());
  }

  @Test
  void rejectsPresenterTextMissingStructuredFact() {
    PlanPresentationFacts facts =
        baseFacts(
            "LangRouter",
            List.of("/lang-router"),
            List.of("preferredLang == 'ru'"),
            List.of("Russian greeting text"),
            List.of(),
            List.of("No MCP"));
    ImplementationPlan structured =
        ImplementationPlanRenderer.render(facts, "render", "1", List.of(), List.of());
    assertTrue(
        !ImplementationPlanRenderer.acceptPresenterText(
            structured, "Plan for LangRouter without the route."));
  }

  private static PlanPresentationFacts baseFacts(
      String chainName,
      List<String> endpoints,
      List<String> branches,
      List<String> scripts,
      List<String> bindings,
      List<String> negatives) {
    return new PlanPresentationFacts(
        "user request",
        chainName,
        "",
        4,
        3,
        List.of(new PlanPresentationNode("n1", "http-trigger", "HTTP", null)),
        List.of(),
        List.of(),
        "GP-01",
        "summary",
        "",
        true,
        "ok",
        true,
        "captured",
        "captured_not_built",
        endpoints,
        branches,
        scripts,
        bindings,
        negatives,
        List.of());
  }
}
