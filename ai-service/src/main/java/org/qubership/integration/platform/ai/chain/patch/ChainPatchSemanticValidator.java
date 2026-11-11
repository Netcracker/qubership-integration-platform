package org.qubership.integration.platform.ai.chain.patch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;

/**
 * Asks whether a patch would leave the chain worse than it found it.
 *
 * <p>Shape and ownership say whether a patch is well-formed and permitted; neither notices that the
 * element it adds is connected to nothing, or that the trigger it removes was the chain's only one.
 * That is what this answers, and it answers it before the reader is asked to approve anything.
 *
 * <p>The verdict is a <em>difference</em>, never an absolute. A chain in the catalog is under no
 * obligation to satisfy the compiler's rules -- it may be mid-edit, hand-built, or older than the
 * rules themselves -- and refusing to patch such a chain would make the feature useless exactly
 * where it is most wanted. Only problems the patch introduces are reported. Problems the chain
 * already had are its owner's business, not this patch's.
 */
@ApplicationScoped
public class ChainPatchSemanticValidator {

  private final CompilerPlanValidator planValidator;

  @Inject
  public ChainPatchSemanticValidator(CompilerPlanValidator planValidator) {
    this.planValidator = Objects.requireNonNull(planValidator, "planValidator");
  }

  /**
   * Problems present in {@code after} that were absent in {@code before}, plus the local rules that
   * only make sense for a patch. An empty list means the patch may proceed.
   */
  public List<String> introducedProblems(ChainPlanGraph before, ChainPlanGraph after, GraphPatch patch) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");

    List<String> problems = new ArrayList<>(siblingPriorityConflicts(before, patch));

    Set<String> knownBefore = problemMessages(before);
    for (String problem : problemMessages(after)) {
      if (!knownBefore.contains(problem)) {
        problems.add(problem);
      }
    }
    return List.copyOf(problems);
  }

  /**
   * Diffing on the message, not the issue id: {@link CompilerPlanValidator} mints ids from a
   * per-run counter, so the same problem carries a different id in each of the two runs and every
   * issue would read as introduced.
   *
   * <p>A set also collapses two identical messages into one, so a patch that adds a second
   * occurrence of a problem the chain already had goes unreported. That fails toward letting a
   * patch through rather than blocking a good one, which is the right way round here.
   */
  private Set<String> problemMessages(ChainPlanGraph graph) {
    Set<String> messages = new LinkedHashSet<>();
    for (ValidationIssue issue : planValidator.validate(new PlanGraphValidationInput(graph)).issues()) {
      if (issue != null && issue.message() != null) {
        messages.add(issue.message());
      }
    }
    return messages;
  }

  /**
   * Refuses a patch that moves more than one branch of the same ordered container.
   *
   * <p>The catalog renumbers the siblings of whichever branch is written, and the writer patches
   * elements in node-id order rather than in the order the patch lists them, so two such changes in
   * one patch resolve in an order nobody chose. One at a time is the only form with a defined
   * outcome.
   */
  private static List<String> siblingPriorityConflicts(ChainPlanGraph before, GraphPatch patch) {
    if (patch == null || patch.propertyPatches() == null) {
      return List.of();
    }
    Map<String, ChainPlanNode> nodesById = new LinkedHashMap<>();
    for (ChainPlanNode node : before.nodes() == null ? List.<ChainPlanNode>of() : before.nodes()) {
      if (node != null && node.nodeId() != null) {
        nodesById.put(node.nodeId(), node);
      }
    }

    Map<String, Set<String>> movedBranchesByParent = new LinkedHashMap<>();
    for (PropertyPatch propertyPatch : patch.propertyPatches()) {
      if (propertyPatch == null
          || propertyPatch.property() == null
          || !PRIORITY_PROPERTY.equals(propertyPatch.property().key())) {
        continue;
      }
      ChainPlanNode node = nodesById.get(propertyPatch.targetNodeId());
      if (node == null || node.parentNodeId() == null) {
        continue;
      }
      movedBranchesByParent
          .computeIfAbsent(node.parentNodeId(), parent -> new LinkedHashSet<>())
          .add(propertyPatch.targetNodeId());
    }

    List<String> conflicts = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : movedBranchesByParent.entrySet()) {
      if (entry.getValue().size() > 1) {
        conflicts.add(
            "moves more than one branch of '"
                + entry.getKey()
                + "' at once ("
                + String.join(", ", entry.getValue())
                + "); move one branch per change and let the catalog renumber the rest");
      }
    }
    return conflicts;
  }

  private static final String PRIORITY_PROPERTY = "priority";
}
