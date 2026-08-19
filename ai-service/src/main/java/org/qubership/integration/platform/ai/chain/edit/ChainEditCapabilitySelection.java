package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>The owner of a change is a fact of the pinned compiler package, not of the wording: a
 * configuration change is routed by matching the property keys the reader named against each
 * generator's declared {@code properties}, the same ownership metadata
 * {@link #configureGeneratorPlans} reads for {@code CONFIGURE}. There is no hand-maintained map from
 * action to skill here -- the mechanism is one, and it is data-driven.
 *
 * <p>The target scope is narrowed the same way. A skill that owns no property of an element's type
 * cannot change it, so leaving that element in the plan buys nothing and costs an ownership refusal
 * the reader cannot act on.
 */
public final class ChainEditCapabilitySelection {

  /** The one type {@code REBIND_SERVICE_CALL} ever targets; not a per-action map. */
  private static final String SERVICE_CALL_TYPE = "service-call";

  private ChainEditCapabilitySelection() {}

  /**
   * The skill that owns this edit, or empty when the pinned package has none.
   *
   * <p>A simple addition prefers the generator that already configures that element type. When the
   * capture names property keys, the owner is the generator that declares those keys for the type,
   * the same match {@link #configureGeneratorPlans} uses. Java places the shell before this owner
   * fills it. Compound additions use {@link #structuralGeneratorPlans} after the shared structure
   * stage instead. A rebind is always a change to a {@code service-call} element, so it is looked
   * up by that fixed type rather than by a separate map entry.
   */
  public static Optional<String> owningSkillId(ResolvedCompilerDag dag, ChainEditIntent intent) {
    String type =
        switch (intent.action()) {
          case ADD_ELEMENTS -> intent.requestedElementType();
          case REBIND_SERVICE_CALL -> SERVICE_CALL_TYPE;
          default -> null;
        };
    if (type == null) {
      return Optional.empty();
    }
    if (!intent.propertyKeys().isEmpty()) {
      return dag.nodes().stream()
          .filter(node -> node.ownership() != null)
          .filter(
              node -> {
                Set<String> declared = node.ownership().properties().get(type);
                return declared != null
                    && intent.propertyKeys().stream().anyMatch(declared::contains);
              })
          .map(ResolvedCompilerNode::skillId)
          .findFirst();
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

  /**
   * Owners for a {@code CONFIGURE} edit, one plan per generator that declares at least one of the
   * requested property keys for a target element's type.
   *
   * <p>This reads ownership the same way an addition does: the target element's type and the
   * requested property keys are matched against each generator's declared {@code properties}. A
   * target element whose type owns none of the requested keys contributes no plan, and a generator
   * is scoped to only the target ids and property keys it actually owns -- two owners of different
   * properties on the same element each get their own slice rather than the whole request.
   */
  public static List<GeneratorPlan> configureGeneratorPlans(
      ResolvedCompilerDag dag, ChainPlanGraph graph, ChainEditIntent intent) {
    Map<String, Set<String>> targetsBySkill = new LinkedHashMap<>();
    Map<String, Set<String>> keysBySkill = new LinkedHashMap<>();
    for (String nodeId : intent.targetNodeIds()) {
      ChainPlanNode targetNode = node(graph, nodeId);
      if (targetNode == null || targetNode.type() == null) {
        continue;
      }
      for (ResolvedCompilerNode candidate : dag.nodes()) {
        GraphPatchOwnershipPolicy ownership = candidate.ownership();
        Set<String> declared = ownership == null ? null : ownership.properties().get(targetNode.type());
        if (declared == null || declared.isEmpty()) {
          continue;
        }
        Set<String> matched = new LinkedHashSet<>(intent.propertyKeys());
        matched.retainAll(declared);
        if (matched.isEmpty()) {
          continue;
        }
        targetsBySkill.computeIfAbsent(candidate.skillId(), unused -> new LinkedHashSet<>()).add(nodeId);
        keysBySkill.computeIfAbsent(candidate.skillId(), unused -> new LinkedHashSet<>()).addAll(matched);
      }
    }
    List<GeneratorPlan> plans = new ArrayList<>();
    for (ResolvedCompilerNode candidate : dag.nodes()) {
      Set<String> targetIds = targetsBySkill.get(candidate.skillId());
      if (targetIds == null || targetIds.isEmpty()) {
        continue;
      }
      String generatorId =
          candidate.generatorId() == null || candidate.generatorId().isBlank()
              ? candidate.skillId()
              : candidate.generatorId();
      plans.add(
          new GeneratorPlan(
              generatorId,
              candidate.skillId(),
              GeneratorPlanStatus.READY,
              List.copyOf(keysBySkill.get(candidate.skillId())),
              List.copyOf(targetIds)));
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
