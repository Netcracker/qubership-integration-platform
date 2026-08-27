package org.qubership.integration.platform.ai.qipknowledge.patch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class GraphPatchOwnershipValidatorTest {

  private GraphPatchOwnershipValidator validator;
  private ValidatedGraphPatchApplier applier;

  @BeforeEach
  void setUp() {
    validator = new GraphPatchOwnershipValidator();
    applier = new ValidatedGraphPatchApplier(validator, new GraphPatchApplier());
  }

  @Test
  void rejectsForeignPropertyWithoutChangingGraph() {
    ChainPlanGraph before = graphWithHttpTrigger();
    GraphPatch patch = patchOwnedBy("cip-timeout-generator", property("http-trigger-1", "roles", "admin"));
    GraphPatchExecutionContext context =
        context(before, ownership(false, false, Set.of(), Set.of(), Map.of("http-trigger", Set.of("timeout"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void acceptedPatchCanAddAnOwnedNodeAndItsEdge() {
    ChainPlanGraph before = graphWithHttpTriggerOnly();
    GraphPatch patch =
        new GraphPatch(
            "patch-1",
            "cip-script-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "script-1",
                        "script",
                        "Script",
                        null,
                        null,
                        List.of(new PlanProperty("script", "return 200"))),
                    null)),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Add script");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(
                true,
                true,
                Set.of("script"),
                Set.of(),
                Map.of("script", Set.of("script"), "http-trigger", Set.of())));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertTrue(result.validationResult().valid());
    assertTrue(result.graph().nodes().stream().anyMatch(node -> node.nodeId().equals("script-1")));
    assertTrue(
        result.graph().edges().stream()
            .anyMatch(
                edge ->
                    edge.fromNodeId().equals("http-trigger-1")
                        && edge.toNodeId().equals("script-1")));
  }

  @Test
  void rejectsNodeRemoveOperation() {
    ChainPlanGraph before = graphWithTwoNodes();
    GraphPatch patch =
        new GraphPatch(
            "patch-remove-node",
            "cip-script-generator",
            List.of(new NodePatch(GraphPatchOperation.REMOVE, null, "script-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Remove node");
    GraphPatchExecutionContext context =
        context(before, ownership(true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsEdgeRemoveOperation() {
    ChainPlanGraph before = graphWithEdge();
    GraphPatch patch =
        new GraphPatch(
            "patch-remove-edge",
            "cip-script-generator",
            List.of(),
            List.of(new EdgePatch(GraphPatchOperation.REMOVE, null, "edge-1")),
            List.of(),
            List.of(),
            List.of(),
            "Remove edge");
    GraphPatchExecutionContext context =
        context(before, ownership(true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsPropertyRemoveOperation() {
    ChainPlanGraph before = graphWithTwoNodes();
    GraphPatch patch =
        new GraphPatch(
            "patch-remove-property",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.REMOVE,
                    "script-1",
                    new PlanProperty("script", null))),
            List.of(),
            List.of(),
            "Remove property");
    GraphPatchExecutionContext context =
        context(before, ownership(true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsChainRemoveOperation() {
    ChainPlanGraph before = graphWithMaskingFields();
    GraphPatch patch =
        new GraphPatch(
            "patch-remove-chain-field",
            "cip-naming-generator",
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ChainPatch(
                    GraphPatchOperation.REMOVE,
                    new PlanProperty("maskedFieldNames", "customerEmail"))),
            List.of(),
            "Remove chain field");
    GraphPatchExecutionContext context =
        context(before, ownership(false, false, Set.of(), Set.of("maskedFieldNames"), Map.of()));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsEdgeAddBetweenTwoPreExistingForeignNodes() {
    ChainPlanGraph before = graphWithForeignNodes();
    GraphPatch patch =
        new GraphPatch(
            "patch-foreign-edge",
            "cip-script-generator",
            List.of(),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("edge-new", "http-trigger-1", "service-call-1", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Add edge between foreign nodes");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(
                false,
                true,
                Set.of("script"),
                Set.of(),
                Map.of("script", Set.of("script"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsUpdateThatMutatesNodeType() {
    ChainPlanGraph before = graphWithTwoNodes();
    GraphPatch patch =
        new GraphPatch(
            "patch-type-mutation",
            "cip-script-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode(
                        "script-1",
                        "http-trigger",
                        "Script",
                        null,
                        null,
                        List.of()),
                    "script-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Mutate type");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(
                true,
                true,
                Set.of("script", "http-trigger"),
                Set.of(),
                Map.of("script", Set.of("script"), "http-trigger", Set.of("timeout"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsUpdateThatEmbedsForeignPropertyKeyInNodeProperties() {
    ChainPlanGraph before = graphWithHttpTrigger();
    GraphPatch patch =
        new GraphPatch(
            "patch-embedded-foreign-property",
            "cip-timeout-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode(
                        "http-trigger-1",
                        "http-trigger",
                        "Trigger",
                        null,
                        null,
                        List.of(new PlanProperty("roles", "admin"))),
                    "http-trigger-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Embed foreign property");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(false, false, Set.of("http-trigger"), Set.of(), Map.of("http-trigger", Set.of("timeout"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsAddThatEmbedsUnownedPropertyKeyInNodeProperties() {
    ChainPlanGraph before = graphWithHttpTriggerOnly();
    GraphPatch patch =
        new GraphPatch(
            "patch-add-unowned-property",
            "cip-script-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "script-1",
                        "script",
                        "Script",
                        null,
                        null,
                        List.of(new PlanProperty("roles", "admin"))),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Add with unowned property");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void acceptsForeignReparentUnderOwnedNodeAddedInSamePatch() {
    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "demo"),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Main workflow step",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return 200")))),
            List.of());
    GraphPatch patch =
        new GraphPatch(
            "patch-eh-reparent",
            "cip-error-handling-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("try-2", "try-2", "Try block", null, null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode(
                        "script-1",
                        "script",
                        "Main workflow step",
                        "try-2",
                        null,
                        List.of()),
                    "script-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Reparent foreign script under owned try-2");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(
                true,
                true,
                Set.of("try-catch-finally-2", "try-2", "catch-2", "finally-2"),
                Set.of(),
                Map.of("catch-2", Set.of("exception", "priority"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertTrue(result.validationResult().valid());
    assertTrue(
        result.graph().nodes().stream()
            .anyMatch(
                node ->
                    node.nodeId().equals("script-1")
                        && "try-2".equals(node.parentNodeId())
                        && "script".equals(node.type())
                        && "Main workflow step".equals(node.label())));
  }

  @Test
  void rejectsReparentOfATriggerUnderOwnedShellAddedInSamePatch() {
    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "demo"),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode(
                    "service-call-1", "service-call", "Call", null, null, List.of())),
            List.of());
    GraphPatch patch =
        wrapperPatch(
            new ChainPlanNode(
                "http-trigger-1",
                "http-trigger",
                "Trigger",
                "try-2",
                null,
                List.of()),
            "http-trigger-1");
    GraphPatchExecutionContext context =
        context(before, ehOwnership(), List.of("service-call-1"));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
    assertTrue(
        result.validationResult().summary().contains("http-trigger-1"),
        result.validationResult().summary());
  }

  @Test
  void acceptsReparentOfNamedEditTargetUnderOwnedShellAddedInSamePatch() {
    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "demo"),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode(
                    "service-call-1", "service-call", "Call", null, null, List.of())),
            List.of());
    GraphPatch patch =
        wrapperPatch(
            new ChainPlanNode(
                "service-call-1",
                "service-call",
                "Call",
                "try-2",
                null,
                List.of()),
            "service-call-1");
    GraphPatchExecutionContext context =
        context(before, ehOwnership(), List.of("service-call-1"));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertTrue(result.validationResult().valid(), result.validationResult().summary());
    assertTrue(
        result.graph().nodes().stream()
            .anyMatch(
                node ->
                    node.nodeId().equals("service-call-1")
                        && "try-2".equals(node.parentNodeId())
                        && "service-call".equals(node.type())));
  }

  @Test
  void rejectsReparentOfANodeThatIsNotAnEditTarget() {
    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "demo"),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode(
                    "service-call-1", "service-call", "Call", null, null, List.of()),
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Main workflow step",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return 200")))),
            List.of());
    GraphPatch patch =
        wrapperPatch(
            new ChainPlanNode(
                "script-1",
                "script",
                "Main workflow step",
                "try-2",
                null,
                List.of()),
            "script-1");
    GraphPatchExecutionContext context =
        context(before, ehOwnership(), List.of("service-call-1"));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsForeignReparentWhenMayAddNodesIsFalse() {
    ChainPlanGraph before = graphWithTwoNodes();
    GraphPatch patch =
        new GraphPatch(
            "patch-foreign-reparent-denied",
            "cip-timeout-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode(
                        "script-1",
                        "script",
                        "Script",
                        "http-trigger-1",
                        null,
                        List.of()),
                    "script-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Reparent with mayAddNodes false");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(
                false,
                false,
                Set.of("http-trigger"),
                Set.of(),
                Map.of("http-trigger", Set.of("timeout"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  @Test
  void rejectsForeignReparentUnderExistingForeignParent() {
    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "demo"),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("service-call-1", "service-call", "Call", null, null, List.of()),
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return 200")))),
            List.of());
    GraphPatch patch =
        new GraphPatch(
            "patch-foreign-parent-reparent",
            "cip-error-handling-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode(
                        "script-1",
                        "script",
                        "Script",
                        "service-call-1",
                        null,
                        List.of()),
                    "script-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Reparent under existing foreign parent");
    GraphPatchExecutionContext context =
        context(
            before,
            ownership(
                true,
                true,
                Set.of("try-catch-finally-2", "try-2", "catch-2", "finally-2"),
                Set.of(),
                Map.of("catch-2", Set.of("exception", "priority"))));

    GraphPatchApplyResult result = applier.apply(context, patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
  }

  private static GraphPatch patchOwnedBy(String owner, PropertyPatch propertyPatch) {
    return new GraphPatch(
        "patch-foreign-property",
        owner,
        List.of(),
        List.of(),
        List.of(propertyPatch),
        List.of(),
        List.of(),
        "Set unsupported property");
  }

  private static PropertyPatch property(String nodeId, String key, String value) {
    return new PropertyPatch(GraphPatchOperation.ADD, nodeId, new PlanProperty(key, value));
  }

  private static GraphPatch wrapperPatch(ChainPlanNode reparented, String targetNodeId) {
    return new GraphPatch(
        "patch-eh-wrap",
        "cip-error-handling-generator",
        List.of(
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode("try-2", "try-2", "Try block", null, null, List.of()),
                null),
            new NodePatch(GraphPatchOperation.UPDATE, reparented, targetNodeId)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Wrap under owned try-2");
  }

  private static GraphPatchOwnershipPolicy ehOwnership() {
    return ownership(
        true,
        true,
        Set.of("try-catch-finally-2", "try-2", "catch-2", "finally-2"),
        Set.of(),
        Map.of("catch-2", Set.of("exception", "priority")));
  }

  private static GraphPatchExecutionContext context(
      ChainPlanGraph inputGraph, GraphPatchOwnershipPolicy ownership) {
    return context(inputGraph, ownership, List.of());
  }

  private static GraphPatchExecutionContext context(
      ChainPlanGraph inputGraph, GraphPatchOwnershipPolicy ownership, List<String> editTargetNodeIds) {
    return new GraphPatchExecutionContext(
        "run-1",
        "cip-script-generator",
        "req",
        "input",
        "compiler",
        "24.4",
        new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
            "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
        List.of(),
        inputGraph,
        ownership,
        "",
        editTargetNodeIds);
  }

  private static GraphPatchOwnershipPolicy ownership(
      boolean mayAddNodes,
      boolean mayAddEdges,
      Set<String> nodeTypes,
      Set<String> chainFields,
      Map<String, Set<String>> properties) {
    return new GraphPatchOwnershipPolicy(mayAddNodes, mayAddEdges, nodeTypes, chainFields, properties);
  }

  private static ChainPlanGraph graphWithHttpTrigger() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }

  private static ChainPlanGraph graphWithHttpTriggerOnly() {
    return graphWithHttpTrigger();
  }

  private static ChainPlanGraph graphWithTwoNodes() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "script-1",
                "script",
                "Script",
                null,
                null,
                List.of(new PlanProperty("script", "return 200")))),
        List.of());
  }

  private static ChainPlanGraph graphWithEdge() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "script-1",
                "script",
                "Script",
                null,
                null,
                List.of(new PlanProperty("script", "return 200")))),
        List.of(new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null)));
  }

  private static ChainPlanGraph graphWithForeignNodes() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("service-call-1", "service-call", "Call", null, null, List.of())),
        List.of());
  }

  private static ChainPlanGraph graphWithMaskingFields() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo", true, List.of("customerEmail"), null, null),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
        List.of(new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null)));
  }
}
