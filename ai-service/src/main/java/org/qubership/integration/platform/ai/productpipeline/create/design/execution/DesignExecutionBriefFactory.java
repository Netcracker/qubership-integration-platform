package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Builds the requirement brief seeded into compiler DAG execution for design-execution.
 *
 * <p>The brief stays user context. The compiled graph is the compiler input. This factory prefers
 * the stored analysis brief and uses the revision identity only when no stored brief exists. It
 * does not invent trigger or step facts from the revision. Mapping rule bodies come from the
 * stored brief; a missing brief at create is a contract failure in the execution runner.
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
   * Keeps approved context separate from repair evidence. The execution request carries repair
   * evidence to the failing skill; it must never become part of the approved task.
   */
  @SuppressWarnings("java:S1172")
  public static RequirementBrief build(
      RequirementBrief storedBrief,
      ChainSemanticRevision revision,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph) {
    return build(storedBrief, revision);
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
            brief.mappingIntents(),
            brief.flow(),
            brief.catalogBindings()).withTargetFolderPath(brief.targetFolderPath()));
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
            List.of()));
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
