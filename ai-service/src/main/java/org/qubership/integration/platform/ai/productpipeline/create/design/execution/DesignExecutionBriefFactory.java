package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Builds the requirement brief seeded into compiler DAG execution for design-execution.
 *
 * <p>The brief stays user context. The compiled graph is the compiler input. This factory prefers
 * the stored analysis brief and uses the revision identity only when no stored brief exists. It
 * does not invent trigger or step facts from the revision.
 */
public final class DesignExecutionBriefFactory {

  private DesignExecutionBriefFactory() {}

  public static RequirementBrief build(
      RequirementBrief storedBrief, ChainSemanticRevision revision) {
    Objects.requireNonNull(revision, "revision");
    if (storedBrief != null) {
      return enrich(storedBrief, revision);
    }
    return fromRevision(revision);
  }

  /**
   * Same brief, plus the halt evidence and the chain-plan graph the failing attempt produced.
   * {@code repairEvidence} and {@code priorGraph} are null on a first turn, in which case this
   * returns exactly what {@link #build(RequirementBrief, ChainSemanticRevision)} does.
   */
  public static RequirementBrief build(
      RequirementBrief storedBrief,
      ChainSemanticRevision revision,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph) {
    RequirementBrief brief = build(storedBrief, revision);
    if (repairEvidence == null || !repairEvidence.hasEvidence()) {
      return brief;
    }
    return brief.withApprovedDraftText(
        withRepairEvidence(brief.approvedDraftText(), repairEvidence, priorGraph));
  }

  private static String withRepairEvidence(
      String draftText, StageRepairEvidence repair, ChainPlanGraph priorGraph) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "Repair the previous design-execution attempt. Correct the step named below instead of "
            + "regenerating the whole chain.\n\n");
    sb.append("Halt repair evidence:\n");
    if (repair.outcomeClass() != null && !repair.outcomeClass().isBlank()) {
      sb.append("- outcomeClass: ").append(repair.outcomeClass().trim()).append('\n');
    }
    if (repair.failedStageId() != null && !repair.failedStageId().isBlank()) {
      sb.append("- failedStageId: ").append(repair.failedStageId().trim()).append('\n');
    }
    if (repair.findings() != null && !repair.findings().isBlank()) {
      sb.append("- validationFindings:\n").append(repair.findings().trim()).append('\n');
    }
    if (repair.errorEvidence() != null && !repair.errorEvidence().isBlank()) {
      sb.append("- errorEvidence:\n").append(repair.errorEvidence().trim()).append('\n');
    }
    if (repair.haltFollowUpText() != null && !repair.haltFollowUpText().isBlank()) {
      sb.append("- haltFollowUpText: ").append(repair.haltFollowUpText().trim()).append('\n');
    }
    if (priorGraph != null) {
      sb.append("\nPrior chain plan graph:\n").append(formatPriorGraph(priorGraph)).append('\n');
    }
    sb.append('\n').append(draftText == null ? "" : draftText);
    return sb.toString();
  }

  private static String formatPriorGraph(ChainPlanGraph graph) {
    StringBuilder body = new StringBuilder();
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null) {
        continue;
      }
      body.append("- ")
          .append(node.nodeId())
          .append(" [")
          .append(node.type())
          .append("] ")
          .append(node.label() == null ? "" : node.label())
          .append('\n');
    }
    return body.toString().trim();
  }

  private static RequirementBrief enrich(
      RequirementBrief brief, ChainSemanticRevision revision) {
    LinkedHashSet<String> inputs = new LinkedHashSet<>(brief.inputs());
    LinkedHashSet<String> constraints = new LinkedHashSet<>(brief.constraints());
    constraints.addAll(revision.constraints());
    return RequirementBriefProjector.project(
        new RequirementBrief(
            firstNonBlank(brief.goal(), revision.chainIdentity()),
            List.copyOf(inputs),
            List.copyOf(constraints),
            brief.assumptions().isEmpty() ? revision.assumptions() : brief.assumptions(),
            brief.citations(),
            firstNonBlank(brief.summary(), revision.chainIdentity()),
            brief.approvedDraftReference(),
            firstNonBlank(brief.approvedDraftText()),
            List.copyOf(brief.facts()),
            brief.entryPoints(),
            brief.serviceCalls(),
            brief.requirements(),
            brief.mappingIntents().isEmpty()
                ? revision.mappingIntents()
                : brief.mappingIntents(),
            brief.flow(),
            brief.catalogBindings()));
  }

  private static RequirementBrief fromRevision(ChainSemanticRevision revision) {
    LinkedHashSet<String> inputs = new LinkedHashSet<>();
    LinkedHashSet<String> constraints = new LinkedHashSet<>(revision.constraints());
    return RequirementBriefProjector.project(
        new RequirementBrief(
            revision.chainIdentity(),
            List.copyOf(inputs),
            List.copyOf(constraints),
            revision.assumptions(),
            List.of(),
            revision.chainIdentity(),
            null,
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            revision.mappingIntents()));
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }
}
