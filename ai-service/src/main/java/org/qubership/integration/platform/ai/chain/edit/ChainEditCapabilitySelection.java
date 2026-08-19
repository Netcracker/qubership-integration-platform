package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanStatus;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/**
 * Which compiler skill owns an edit, and which of its target elements it may actually touch.
 *
 * <p>The owner of a configuration change is a fact of the pinned compiler package, not of the
 * wording: authentication, timeouts, retries and security belong to different skills with different
 * ownership contracts, and sending a timeout request to the skill that may rewrite credentials
 * widens the change beyond what the reader asked for.
 *
 * <p>The target scope is narrowed the same way. A skill that owns no property of an element's type
 * cannot change it, so leaving that element in the plan buys nothing and costs an ownership refusal
 * the reader cannot act on.
 */
public final class ChainEditCapabilitySelection {

  private static final Map<ChainEditAction, String> BY_ACTION =
      Map.of(
          ChainEditAction.REBIND_SERVICE_CALL, "cip-service-call-generator",
          ChainEditAction.EDIT_SCRIPT, "cip-script-generator",
          ChainEditAction.EDIT_AUTHENTICATION, "cip-auth-generator",
          ChainEditAction.EDIT_TIMEOUT, "cip-timeout-generator",
          ChainEditAction.EDIT_RETRY, "cip-retry-generator",
          ChainEditAction.EDIT_SECURITY, "cip-security-generator");

  private ChainEditCapabilitySelection() {}

  /**
   * The skill that owns this edit, or empty when the pinned package has none.
   *
   * <p>A simple addition prefers the generator that already configures that element type. Java
   * places its shell before this owner fills it. Compound additions use
   * {@link #structuralGeneratorPlans} after the shared structure stage instead.
   */
  public static Optional<String> owningSkillId(ResolvedCompilerDag dag, ChainEditIntent intent) {
    if (intent.action() == ChainEditAction.ADD_ELEMENTS) {
      String type = intent.requestedElementType();
      if (type == null) {
        return Optional.empty();
      }
      Optional<String> propertyOwner =
          dag.nodes().stream()
              .filter(node -> node.ownership() != null && node.ownership().properties().containsKey(type))
              .map(ResolvedCompilerNode::skillId)
              .findFirst();
      if (propertyOwner.isPresent()) {
        return propertyOwner;
      }
      return dag.nodes().stream()
          .filter(node -> node.ownership() != null && node.ownership().mayAddNodes())
          .filter(node -> node.ownership().nodeTypes().contains(type))
          .map(ResolvedCompilerNode::skillId)
          .findFirst();
    }
    String skillId = BY_ACTION.get(intent.action());
    if (skillId == null) {
      return Optional.empty();
    }
    return dag.nodes().stream()
        .map(ResolvedCompilerNode::skillId)
        .filter(skillId::equals)
        .findFirst();
  }

  /**
   * The requested targets this skill may change.
   *
   * <p>An addition keeps every target: they are the elements the new one goes next to, not the
   * elements being changed, and the placing skill needs to see them.
   */
  public static List<String> scopedTargets(
      ResolvedCompilerDag dag, String skillId, ChainEditIntent intent, ChainPlanGraph graph) {
    if (intent.action() == ChainEditAction.ADD_ELEMENTS) {
      return intent.targetNodeIds();
    }
    GraphPatchOwnershipPolicy ownership = ownershipOf(dag, skillId);
    if (ownership == null) {
      return intent.targetNodeIds();
    }
    List<String> scoped = new ArrayList<>();
    for (String nodeId : intent.targetNodeIds()) {
      ChainPlanNode node = node(graph, nodeId);
      if (node == null || node.type() == null) {
        continue;
      }
      if (ownership.nodeTypes().contains(node.type())
          || ownership.properties().containsKey(node.type())) {
        scoped.add(nodeId);
      }
    }
    return List.copyOf(scoped);
  }

  /**
   * Configuration owners for nodes introduced by a structure capture.
   *
   * <p>Structure owns node placement. A downstream generator is selected only when its pinned
   * ownership metadata declares properties for one of the new node types, and it receives only
   * those new node ids.
   */
  public static List<GeneratorPlan> structuralGeneratorPlans(
      ResolvedCompilerDag dag,
      ChainPlanGraph base,
      ChainPlanGraph structured,
      ChainEditIntent intent) {
    Set<String> baseNodeIds = new LinkedHashSet<>();
    if (base.nodes() != null) {
      for (ChainPlanNode node : base.nodes()) {
        if (node != null && node.nodeId() != null) {
          baseNodeIds.add(node.nodeId());
        }
      }
    }
    List<ChainPlanNode> addedNodes =
        structured.nodes() == null
            ? List.of()
            : structured.nodes().stream()
                .filter(node -> node != null && !baseNodeIds.contains(node.nodeId()))
                .toList();
    List<GeneratorPlan> plans = new ArrayList<>();
    for (ResolvedCompilerNode node : dag.nodes()) {
      GraphPatchOwnershipPolicy ownership = node.ownership();
      if (ownership == null || ownership.properties().isEmpty()) {
        continue;
      }
      List<String> targetNodeIds =
          addedNodes.stream()
              .filter(candidate -> ownership.properties().containsKey(candidate.type()))
              .map(ChainPlanNode::nodeId)
              .toList();
      if (targetNodeIds.isEmpty()) {
        continue;
      }
      String generatorId =
          node.generatorId() == null || node.generatorId().isBlank()
              ? node.skillId()
              : node.generatorId();
      plans.add(
          new GeneratorPlan(
              generatorId,
              node.skillId(),
              GeneratorPlanStatus.READY,
              List.of(intent.action().name()),
              targetNodeIds));
    }
    return List.copyOf(plans);
  }

  private static GraphPatchOwnershipPolicy ownershipOf(ResolvedCompilerDag dag, String skillId) {
    return dag.nodes().stream()
        .filter(node -> skillId.equals(node.skillId()))
        .map(ResolvedCompilerNode::ownership)
        .findFirst()
        .orElse(null);
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    if (graph.nodes() == null) {
      return null;
    }
    return graph.nodes().stream()
        .filter(candidate -> candidate != null && nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElse(null);
  }
}
