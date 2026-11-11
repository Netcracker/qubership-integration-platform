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
    List<String> affectedGraphElementIds,
    List<CreationAllowance> creationAllowances,
    List<String> replacementIds,
    String taskKey,
    WorkTaskKind taskKind,
    String inputFingerprint,
    FixedTransferEndpoint fixedEndpoint) {

  public WorkTaskScope {
    ownedRecordIds = Lists.copy(ownedRecordIds);
    requiredInputs = Lists.copy(requiredInputs);
    affectedGraphElementIds = Lists.copy(affectedGraphElementIds);
    creationAllowances = Lists.copy(creationAllowances);
    replacementIds = Lists.copy(replacementIds);
    taskKey = taskKey == null || taskKey.isBlank() ? taskId : taskKey;
    taskKind = taskKind == null ? WorkTaskKind.UNSPECIFIED : taskKind;
    inputFingerprint = inputFingerprint == null ? "" : inputFingerprint;
  }

  /**
   * Compatibility constructor. Creation stays closed until allowances name a kind and parent.
   * Replacement ids default to the owned ids when replacement is permitted.
   */
  public WorkTaskScope(
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
    this(
        taskId,
        baseRevision,
        stage,
        skillId,
        ownedRecordIds,
        createPermitted,
        replacePermitted,
        deletePermitted,
        requiredInputs,
        affectedGraphElementIds,
        List.of(),
        replacePermitted ? ownedRecordIds : List.of(),
        taskId,
        WorkTaskKind.UNSPECIFIED,
        "",
        null);
  }

  public boolean allowsCreation(WorkRecordKind kind, String parentId) {
    String parent = parentId == null ? "" : parentId;
    for (CreationAllowance allowance : creationAllowances) {
      if (allowance.kind() != kind) {
        continue;
      }
      if (allowance.parentId().isBlank() || allowance.parentId().equals(parent)) {
        return true;
      }
    }
    return false;
  }

  public boolean allowsReplacement(String recordId) {
    return replacePermitted && replacementIds.contains(recordId);
  }
}
