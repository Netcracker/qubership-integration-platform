package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;

class RequirementTopologyGuardTest {

  private final RequirementTopologyGuard guard = new RequirementTopologyGuard();

  @Test
  void rejectsErrorHandlingElementsWhenNegativeFactForbidsThem() {
    List<RequirementFact> facts = RequirementFactFixtures.greetingsApprovedDraft().facts();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("greetings", "Greetings"),
            List.of(
                new ChainPlanNode("t1", "http-trigger", "HTTP", null, null, List.of()),
                new ChainPlanNode(
                    "tcff", "try-catch-finally-2", "EH", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("s1", "script", "Hello", "try", null, List.of())),
            List.of());

    List<String> blockers = guard.evaluateAfterGraphCapture(facts, graph);
    assertFalse(blockers.isEmpty());
    assertTrue(blockers.stream().anyMatch(b -> b.contains("try-catch-finally-2")));
  }

  @Test
  void rejectsErrorHandlingGeneratorWhenNegativeFactForbidsIt() {
    List<RequirementFact> facts = RequirementFactFixtures.greetingsApprovedDraft().facts();
    List<String> blockers =
        guard.evaluateAfterGeneratorManifest(facts, List.of("cip-error-handling-generator"));
    assertFalse(blockers.isEmpty());
    assertTrue(blockers.stream().anyMatch(b -> b.contains("cip-error-handling-generator")));
  }

  @Test
  void acceptsDirectHttpToScriptWhenErrorHandlingForbidden() {
    List<RequirementFact> facts = RequirementFactFixtures.greetingsApprovedDraft().facts();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("greetings", "Greetings"),
            List.of(
                new ChainPlanNode("t1", "http-trigger", "HTTP", null, null, List.of()),
                new ChainPlanNode("s1", "script", "Hello", null, null, List.of())),
            List.of());

    assertTrue(guard.evaluateAfterGraphCapture(facts, graph).isEmpty());
    assertTrue(guard.evaluateAfterGeneratorManifest(facts, List.of("cip-script-generator")).isEmpty());
  }
}
