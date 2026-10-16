package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class ChainPatchSemanticValidatorTest {

  private CompilerPlanValidator planValidator;
  private ChainPatchSemanticValidator validator;

  @BeforeEach
  void setUp() {
    planValidator = mock(CompilerPlanValidator.class);
    validator = new ChainPatchSemanticValidator(planValidator);
  }

  @Test
  void reportsAProblemThePatchIntroduced() {
    validatorSees(before(), List.of());
    validatorSees(after(), List.of("VR-G-004: 'node-new' is unreachable from a trigger"));

    List<String> problems = validator.introducedProblems(before(), after(), noPropertyPatches());

    assertEquals(List.of("VR-G-004: 'node-new' is unreachable from a trigger"), problems);
  }

  @Test
  void staysSilentAboutAProblemTheChainAlreadyHad() {
    // A chain in the catalog owes the compiler's rules nothing -- it may be mid-edit or older than
    // the rules. Refusing to patch it because of a problem the patch did not cause would make the
    // feature useless exactly where it is most wanted.
    validatorSees(before(), List.of("VR-G-001: Chain has no trigger element"));
    validatorSees(after(), List.of("VR-G-001: Chain has no trigger element"));

    assertTrue(validator.introducedProblems(before(), after(), noPropertyPatches()).isEmpty());
  }

  @Test
  void separatesAnIntroducedProblemFromAPreExistingOne() {
    validatorSees(before(), List.of("VR-G-001: Chain has no trigger element"));
    validatorSees(
        after(),
        List.of("VR-G-001: Chain has no trigger element", "VR-G-004: 'node-new' is unreachable"));

    List<String> problems = validator.introducedProblems(before(), after(), noPropertyPatches());

    assertEquals(List.of("VR-G-004: 'node-new' is unreachable"), problems);
  }

  @Test
  void ignoresTheIssueIdWhichChurnsBetweenRuns() {
    // CompilerPlanValidator mints ids from a per-run counter, so the same problem carries a
    // different id in each of the two runs. Diffing on the id would report everything as new.
    validatorSees(before(), issue("validation-1", "VR-G-001: Chain has no trigger element"));
    validatorSees(after(), issue("validation-7", "VR-G-001: Chain has no trigger element"));

    assertTrue(validator.introducedProblems(before(), after(), noPropertyPatches()).isEmpty());
  }

  @Test
  void refusesMovingTwoBranchesOfTheSameContainerAtOnce() {
    // The catalog renumbers siblings around whichever branch is written, and the writer patches in
    // node-id order rather than patch order, so two moves in one patch resolve in an order nobody
    // chose.
    validatorSees(branchedChain(), List.of());

    List<String> problems =
        validator.introducedProblems(
            branchedChain(),
            branchedChain(),
            new GraphPatch(
                "patch-1",
                "chain-patch",
                null,
                null,
                List.of(
                    priorityPatch("catch-a", "0"),
                    priorityPatch("catch-b", "1")),
                null,
                List.of(),
                "reorders both branches"));

    assertEquals(1, problems.size());
    assertTrue(problems.get(0).contains("more than one branch"), problems.get(0));
  }

  @Test
  void allowsMovingOneBranch() {
    validatorSees(branchedChain(), List.of());

    List<String> problems =
        validator.introducedProblems(
            branchedChain(),
            branchedChain(),
            new GraphPatch(
                "patch-1",
                "chain-patch",
                null,
                null,
                List.of(priorityPatch("catch-a", "0")),
                null,
                List.of(),
                "reorders one branch"));

    assertTrue(problems.isEmpty(), String.valueOf(problems));
  }

  /** Stubs the plan validator's verdict for one graph, identified by its node ids. */
  private void validatorSees(ChainPlanGraph graph, List<String> messages) {
    validatorSees(
        graph,
        messages.stream().map(message -> issue("validation-1", message)).toArray(ValidationIssue[]::new));
  }

  private void validatorSees(ChainPlanGraph graph, ValidationIssue... issues) {
    List<ValidationIssue> found = List.of(issues);
    when(planValidator.validate(matching(graph)))
        .thenReturn(new ValidationResult(found.isEmpty(), found, "summary"));
  }

  private static ValidationIssue issue(String issueId, String message) {
    return new ValidationIssue(
        issueId, ValidationSeverity.BLOCKER, message, "plan-validator", List.of(), List.of(), null);
  }

  private static GraphPatch noPropertyPatches() {
    return new GraphPatch("patch-1", "chain-patch", null, null, List.of(), null, List.of(), "");
  }

  private static PropertyPatch priorityPatch(String nodeId, String value) {
    return new PropertyPatch(
        GraphPatchOperation.UPDATE, nodeId, new PlanProperty("priority", value));
  }

  private static ChainPlanGraph before() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Order sync", null),
        List.of(new ChainPlanNode("element-script", "script", "Normalize", null, null, List.of())),
        List.of());
  }

  private static ChainPlanGraph after() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Order sync", null),
        List.of(
            new ChainPlanNode("element-script", "script", "Normalize", null, null, List.of()),
            new ChainPlanNode("node-new", "script", "Enrich", null, null, List.of())),
        List.of());
  }

  private static ChainPlanGraph branchedChain() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Order sync", null),
        List.of(
            new ChainPlanNode("tcf", "try-catch-finally-2", "Handle", null, null, List.of()),
            new ChainPlanNode("catch-a", "catch-2", "Catch A", "tcf", null, List.of()),
            new ChainPlanNode("catch-b", "catch-2", "Catch B", "tcf", null, List.of())),
        List.of());
  }

  private static PlanGraphValidationInput matching(ChainPlanGraph graph) {
    return org.mockito.ArgumentMatchers.argThat(
        input -> input != null && sameNodeIds(input.graph(), graph));
  }

  private static boolean sameNodeIds(ChainPlanGraph left, ChainPlanGraph right) {
    return left != null
        && right != null
        && left.nodes().stream().map(ChainPlanNode::nodeId).toList()
            .equals(right.nodes().stream().map(ChainPlanNode::nodeId).toList());
  }
}
