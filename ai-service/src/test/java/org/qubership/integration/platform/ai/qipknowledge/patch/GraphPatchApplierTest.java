package org.qubership.integration.platform.ai.qipknowledge.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class GraphPatchApplierTest {

  private GraphPatchApplier applier;
  private ChainPlanGraph baseGraph;

  @BeforeEach
  void setUp() {
    applier = new GraphPatchApplier();
    baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode(
                    "n2",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return body")))),
            List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
  }

  @Test
  void appliesAddNodePatchWithoutMutatingInputGraph() {
    GraphPatch patch =
        new GraphPatch(
            "patch-add-node",
            "cip-graph-builder",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("n3", "log-2", "Log", null, null, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Add log node");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertTrue(result.applied());
    assertEquals(3, result.graph().nodes().size());
    assertEquals(2, baseGraph.nodes().size());
    assertNotSame(baseGraph, result.graph());
    assertEquals("n3", result.graph().nodes().get(2).nodeId());
    assertEquals("1.0", result.graph().schemaVersion());
    assertEquals("demo-chain", result.graph().chain().name());
  }

  @Test
  void rejectsDuplicateNodeAddWithBlockingIssue() {
    GraphPatch patch =
        new GraphPatch(
            "patch-dup-node",
            "cip-graph-builder",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("n1", "log-2", "Duplicate", null, null, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Duplicate node");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertFalse(result.applied());
    assertTrue(result.validationResult().hasBlockingIssues());
    assertEquals(ValidationSeverity.BLOCKER, result.validationResult().issues().get(0).severity());
    assertEquals(2, result.graph().nodes().size());
    assertEquals(2, baseGraph.nodes().size());
  }

  @Test
  void appliesUpdateNodePatchWhenIdsMatch() {
    GraphPatch patch =
        new GraphPatch(
            "patch-update-node",
            "cip-graph-builder",
            List.of(
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode("n2", "script", "Updated Script", null, null, List.of()),
                    "n2")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Rename script node");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertTrue(result.applied());
    assertEquals("Updated Script", findNode(result.graph(), "n2").label());
    assertEquals("return body", propertyValue(result.graph(), "n2", "script"));
    assertEquals("Script", findNode(baseGraph, "n2").label());
  }

  @Test
  void updateNodePatchPreservesPropertiesWhenIncomingPropertiesEmpty() {
    GraphPatch patch =
        new GraphPatch(
            "patch-rename-label",
            "cip-naming-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode("n2", "script", "Renamed Script", null, null, List.of()),
                    "n2")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Rename script label only");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertTrue(result.applied());
    assertEquals("Renamed Script", findNode(result.graph(), "n2").label());
    assertEquals("return body", propertyValue(result.graph(), "n2", "script"));
  }

  @Test
  void rejectsNodeRemoveWhenEdgeStillReferencesNode() {
    GraphPatch patch =
        new GraphPatch(
            "patch-remove-node",
            "cip-graph-builder",
            List.of(new NodePatch(GraphPatchOperation.REMOVE, null, "n2")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Remove script node");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertFalse(result.applied());
    assertTrue(result.validationResult().hasBlockingIssues());
    assertEquals(2, result.graph().nodes().size());
  }

  @Test
  void appliesEdgeUpdateBeforeDeferredNodeRemove() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("greetings", "Greetings"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", null, null, List.of())),
            List.of(
                new ChainPlanEdge("e1", "trigger", "try", null),
                new ChainPlanEdge("e2", "try", "script", null)));
    GraphPatch patch =
        new GraphPatch(
            "remove-orphan-error-handling",
            "cip-error-handling-generator",
            List.of(new NodePatch(GraphPatchOperation.REMOVE, null, "try")),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanEdge("e1", "trigger", "script", null),
                    "e1"),
                new EdgePatch(GraphPatchOperation.REMOVE, null, "e2")),
            List.of(),
            List.of(),
            List.of(),
            "Remove orphan EH shell");

    GraphPatchApplyResult result = applier.apply(graph, patch);

    assertTrue(result.applied());
    assertEquals(2, result.graph().nodes().size());
    assertEquals(1, result.graph().edges().size());
    assertEquals("script", result.graph().edges().getFirst().toNodeId());
  }

  @Test
  void retargetsExistingWrapEdgeWhenEdgeIdMatchesTargetEdgeId() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("main-step", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("trigger->main-step", "trigger", "main-step", null)));
    GraphPatch patch =
        new GraphPatch(
            "add-try-catch-wrapper-atomic",
            "cip-error-handling-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "eh-wrap", "try-catch-finally-2", "Error handling", null, null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("try-shell", "try-2", "Try", "eh-wrap", null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode(
                        "main-step", "script", "Script", "try-shell", null, List.of()),
                    "main-step")),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanEdge("trigger->main-step", "trigger", "eh-wrap", null),
                    "trigger->main-step")),
            List.of(),
            List.of(),
            List.of(),
            "Wrap script; keep catalog edge id");

    GraphPatchApplyResult result = applier.apply(graph, patch);

    assertTrue(result.applied());
    assertEquals(1, result.graph().edges().size());
    assertEquals("trigger->main-step", result.graph().edges().getFirst().edgeId());
    assertEquals("eh-wrap", result.graph().edges().getFirst().toNodeId());
    assertEquals("try-shell", findNode(result.graph(), "main-step").parentNodeId());
  }

  @Test
  void wrapKeepsEveryNodeReachableFromTheTrigger() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("main-step", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("trigger->main-step", "trigger", "main-step", null)));
    GraphPatch patch =
        new GraphPatch(
            "add-try-catch-wrapper-atomic",
            "cip-error-handling-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "eh-wrap", "try-catch-finally-2", "Error handling", null, null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("try-shell", "try-2", "Try", "eh-wrap", null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "catch-shell", "catch-2", "Catch", "eh-wrap", null, List.of()),
                    null),
                new NodePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanNode(
                        "main-step", "script", "Script", "try-shell", null, List.of()),
                    "main-step")),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanEdge("trigger->main-step", "trigger", "eh-wrap", null),
                    "trigger->main-step")),
            List.of(),
            List.of(),
            List.of(),
            "Wrap script; keep catalog edge id");

    GraphPatchApplyResult result = applier.apply(graph, patch);

    assertTrue(result.applied());
    Set<String> reachable = reachableFromTriggers(result.graph());
    assertTrue(reachable.contains("eh-wrap"), reachable.toString());
    assertTrue(reachable.contains("try-shell"), reachable.toString());
    assertTrue(reachable.contains("catch-shell"), reachable.toString());
    assertTrue(reachable.contains("main-step"), reachable.toString());
  }

  @Test
  void rejectsEdgeUpdateWhenEdgeIdDoesNotMatchTargetEdgeId() {
    GraphPatch patch =
        new GraphPatch(
            "add-try-catch-wrapper-atomic",
            "cip-error-handling-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "eh-wrap", "try-catch-finally-2", "Error handling", null, null, List.of()),
                    null)),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.UPDATE,
                    new ChainPlanEdge("n1->eh-wrap", "n1", "eh-wrap", null),
                    "e1")),
            List.of(),
            List.of(),
            List.of(),
            "Rename edge id while wrapping");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertFalse(result.applied());
    assertTrue(result.validationResult().hasBlockingIssues());
    assertEquals(
        "UPDATE edge patch edgeId 'n1->eh-wrap' does not match targetEdgeId 'e1'",
        result.validationResult().issues().get(0).message());
    assertEquals("e1", baseGraph.edges().getFirst().edgeId());
    assertEquals("n2", baseGraph.edges().getFirst().toNodeId());
  }

  @Test
  void appliesAddEdgePatchWhenReferencedNodesExist() {
    GraphPatch patch =
        new GraphPatch(
            "patch-add-edge",
            "cip-routing-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("n3", "log-2", "Log", null, null, List.of()),
                    null)),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("e2", "n2", "n3", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Extend chain");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertTrue(result.applied());
    assertEquals(1, baseGraph.edges().size());
    assertEquals(2, result.graph().edges().size());
    assertEquals("e2", result.graph().edges().get(1).edgeId());
  }

  @Test
  void rejectsEdgeAddWhenToNodeIdIsUnknown() {
    GraphPatch patch =
        new GraphPatch(
            "patch-bad-edge",
            "cip-routing-generator",
            List.of(),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("e2", "n1", "missing", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Invalid edge");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertFalse(result.applied());
    assertTrue(result.validationResult().hasBlockingIssues());
    assertEquals(1, result.graph().edges().size());
  }

  @Test
  void appliesPropertyAddUpdateAndRemoveByKey() {
    GraphPatch addPatch =
        new GraphPatch(
            "patch-add-prop",
            "cip-error-handling-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "n1",
                    new PlanProperty("timeout", "30"))),
            List.of(),
            List.of(),
            "Add timeout");

    GraphPatchApplyResult addResult = applier.apply(baseGraph, addPatch);
    assertTrue(addResult.applied());
    assertEquals("30", findProperty(findNode(addResult.graph(), "n1"), "timeout").value());

    GraphPatch updatePatch =
        new GraphPatch(
            "patch-update-prop",
            "cip-error-handling-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.UPDATE,
                    "n2",
                    new PlanProperty("script", "exchange.setProperty('ok', true)"))),
            List.of(),
            List.of(),
            "Update script");

    GraphPatchApplyResult updateResult = applier.apply(addResult.graph(), updatePatch);
    assertTrue(updateResult.applied());
    assertEquals(
        "exchange.setProperty('ok', true)",
        findProperty(findNode(updateResult.graph(), "n2"), "script").value());

    GraphPatch removePatch =
        new GraphPatch(
            "patch-remove-prop",
            "cip-error-handling-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.REMOVE, "n1", new PlanProperty("timeout", null))),
            List.of(),
            List.of(),
            "Remove timeout");

    GraphPatchApplyResult removeResult = applier.apply(updateResult.graph(), removePatch);
    assertTrue(removeResult.applied());
    assertFalse(hasProperty(findNode(removeResult.graph(), "n1"), "timeout"));
  }

  @Test
  void upsertsPropertyWhenAddTargetsExistingKeyOrUpdateTargetsMissingKey() {
    GraphPatch addOnExisting =
        new GraphPatch(
            "patch-upsert-add",
            "cip-security-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "n1",
                    new PlanProperty("accessControlType", "RBAC"))),
            List.of(),
            List.of(),
            "Add access control");

    GraphPatchApplyResult addResult = applier.apply(baseGraph, addOnExisting);
    assertTrue(addResult.applied());
    assertEquals("RBAC", findProperty(findNode(addResult.graph(), "n1"), "accessControlType").value());

    GraphPatch updateMissing =
        new GraphPatch(
            "patch-upsert-update",
            "cip-security-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.UPDATE,
                    "n1",
                    new PlanProperty("roles", "[\"qip-viewer\"]"))),
            List.of(),
            List.of(),
            "Add roles via update");

    GraphPatchApplyResult updateResult = applier.apply(addResult.graph(), updateMissing);
    assertTrue(updateResult.applied());
    assertEquals(
        "[\"qip-viewer\"]", findProperty(findNode(updateResult.graph(), "n1"), "roles").value());
  }

  @Test
  void rejectsPropertyPatchWhenTargetNodeIsMissing() {
    GraphPatch patch =
        new GraphPatch(
            "patch-missing-node",
            "cip-error-handling-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "missing",
                    new PlanProperty("timeout", "30"))),
            List.of(),
            List.of(),
            "Missing node");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertFalse(result.applied());
    assertTrue(result.validationResult().hasBlockingIssues());
    assertEquals(2, result.graph().nodes().size());
  }

  @Test
  void preservesUnrelatedNodesAndEdgesOnSuccessfulPatch() {
    GraphPatch patch =
        new GraphPatch(
            "patch-preserve",
            "cip-graph-builder",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("n3", "log-2", "Log", null, null, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Add log node");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertEquals(findNode(baseGraph, "n1"), findNode(result.graph(), "n1"));
    assertEquals(baseGraph.edges(), result.graph().edges());
  }

  @Test
  void appliesChainMaskingPatches() {
    GraphPatch patch =
        new GraphPatch(
            "patch-masking",
            "cip-security-generator",
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ChainPatch(
                    GraphPatchOperation.UPDATE,
                    new PlanProperty("maskingEnabled", "true")),
                new ChainPatch(
                    GraphPatchOperation.ADD,
                    new PlanProperty("maskedFieldNames", "customerEmail")),
                new ChainPatch(
                    GraphPatchOperation.ADD,
                    new PlanProperty("maskedFieldNames", "customerPhone"))),
            List.of(),
            "Enable chain masking");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertTrue(result.applied());
    assertEquals(Boolean.TRUE, result.graph().chain().maskingEnabled());
    assertEquals(List.of("customerEmail", "customerPhone"), result.graph().chain().maskedFieldNames());
    assertNull(baseGraph.chain().maskingEnabled());
  }

  @Test
  void appliesChainNameAndDescriptionUpdates() {
    GraphPatch patch =
        new GraphPatch(
            "patch-chain-name",
            "cip-naming-generator",
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ChainPatch(
                    GraphPatchOperation.UPDATE, new PlanProperty("name", "Demo.Internal.Process")),
                new ChainPatch(
                    GraphPatchOperation.UPDATE,
                    new PlanProperty("description", "Demo chain with corporate naming"))),
            List.of(),
            "Normalize chain metadata");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertTrue(result.applied());
    assertEquals("Demo.Internal.Process", result.graph().chain().name());
    assertEquals("Demo chain with corporate naming", result.graph().chain().description());
    assertEquals("demo-chain", baseGraph.chain().name());
  }

  private static Set<String> reachableFromTriggers(ChainPlanGraph graph) {
    Set<String> reachable = new HashSet<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node.type() != null && node.type().contains("trigger")) {
        reachable.add(node.nodeId());
      }
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (ChainPlanNode node : graph.nodes()) {
        if (node.parentNodeId() != null
            && reachable.contains(node.parentNodeId())
            && reachable.add(node.nodeId())) {
          changed = true;
        }
      }
      if (graph.edges() != null) {
        for (ChainPlanEdge edge : graph.edges()) {
          if (reachable.contains(edge.fromNodeId()) && reachable.add(edge.toNodeId())) {
            changed = true;
          }
        }
      }
    }
    return reachable;
  }

  private static ChainPlanNode findNode(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> node.nodeId().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static PlanProperty findProperty(ChainPlanNode node, String key) {
    return node.properties().stream()
        .filter(property -> property.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static boolean hasProperty(ChainPlanNode node, String key) {
    return node.properties() != null
        && node.properties().stream().anyMatch(property -> property.key().equals(key));
  }

  private static String propertyValue(ChainPlanGraph graph, String nodeId, String key) {
    return findProperty(findNode(graph, nodeId), key).value();
  }
}
