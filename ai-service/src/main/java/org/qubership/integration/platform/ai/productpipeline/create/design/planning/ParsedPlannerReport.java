package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;

/** Parsed fields extracted from a {@code cip-design-planner} Markdown report. */
public record ParsedPlannerReport(List<Step> steps, String apiRelease) {

  public enum OwnerKind {
    SKILL,
    APIHUB_TOOL
  }

  public ParsedPlannerReport {
    steps = DesignArtifacts.copyList(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("steps must not be empty");
    }
    apiRelease = DesignArtifacts.nullableTrimmed(apiRelease);
  }

  public record Step(
      int reportOrdinal,
      String reportText,
      OwnerKind ownerKind,
      List<String> owningSkillIds,
      List<String> toolOperationRefs,
      List<String> participantRefs,
      List<String> operationQueryRefs) {

    public Step {
      if (reportOrdinal < 1) {
        throw new IllegalArgumentException("reportOrdinal must be >= 1");
      }
      reportText = DesignArtifacts.requireText(reportText, "reportText");
      ownerKind = DesignArtifacts.requireNonNull(ownerKind, "ownerKind");
      owningSkillIds = DesignArtifacts.copyList(owningSkillIds);
      toolOperationRefs = DesignArtifacts.copyList(toolOperationRefs);
      participantRefs = DesignArtifacts.copyList(participantRefs);
      operationQueryRefs = DesignArtifacts.copyList(operationQueryRefs);
    }

    DesignExecutionPlan.OwnerKind toPlanOwnerKind() {
      return ownerKind == OwnerKind.APIHUB_TOOL
          ? DesignExecutionPlan.OwnerKind.APIHUB_TOOL
          : DesignExecutionPlan.OwnerKind.SKILL;
    }
  }
}
