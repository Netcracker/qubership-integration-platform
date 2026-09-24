package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/** Server-owned permission for one model task. The model cannot widen it. */
public record WorkTaskScope(
    String taskId,
    String baseRevision,
    WorkStage stage,
    String skillId,
    List<String> ownedRecordIds,
    boolean createPermitted,
    boolean replacePermitted,
    boolean deletePermitted,
    List<String> requiredInputs,
    List<String> affectedGraphElementIds) {

  public WorkTaskScope {
    ownedRecordIds = Lists.copy(ownedRecordIds);
    requiredInputs = Lists.copy(requiredInputs);
    affectedGraphElementIds = Lists.copy(affectedGraphElementIds);
  }
}
