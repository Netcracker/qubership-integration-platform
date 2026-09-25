package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode;

/**
 * Model capture for one scoped task. Permanent ids, approval, progress, and resolved catalog
 * metadata are absent so the model cannot write them.
 */
public record WorkTaskCapture(
    WorkOutcome outcome,
    List<CapturedRequirement> requirements,
    List<CapturedStep> steps,
    List<CapturedConnection> connections,
    List<CapturedSequenceGroup> sequenceGroups,
    List<CapturedConditionGroup> conditionGroups,
    List<CapturedSplitGroup> splitGroups,
    List<CapturedLoopGroup> loopGroups,
    List<CapturedRetryGroup> retryGroups,
    List<CapturedErrorScopeGroup> errorScopeGroups,
    List<CapturedTransfer> transfers,
    List<CapturedRule> rules,
    List<CapturedRetainedValue> retainedValues,
    List<CapturedDelete> deletes,
    String question,
    String unresolvedChoice,
    List<String> clarificationEvidenceIds,
    String defectRecordRef,
    String contradiction,
    List<String> defectEvidenceIds,
    String issueCategory) {

  public WorkTaskCapture {
    requirements = Lists.copy(requirements);
    steps = Lists.copy(steps);
    connections = Lists.copy(connections);
    sequenceGroups = Lists.copy(sequenceGroups);
    conditionGroups = Lists.copy(conditionGroups);
    splitGroups = Lists.copy(splitGroups);
    loopGroups = Lists.copy(loopGroups);
    retryGroups = Lists.copy(retryGroups);
    errorScopeGroups = Lists.copy(errorScopeGroups);
    transfers = Lists.copy(transfers);
    rules = Lists.copy(rules);
    retainedValues = Lists.copy(retainedValues);
    deletes = Lists.copy(deletes);
    question = question == null ? "" : question;
    unresolvedChoice = unresolvedChoice == null ? "" : unresolvedChoice;
    clarificationEvidenceIds = Lists.copy(clarificationEvidenceIds);
    defectRecordRef = defectRecordRef == null ? "" : defectRecordRef;
    contradiction = contradiction == null ? "" : contradiction;
    defectEvidenceIds = Lists.copy(defectEvidenceIds);
    issueCategory = issueCategory == null ? "" : issueCategory;
  }

  public static WorkTaskCapture prepared(
      List<CapturedRequirement> requirements,
      List<CapturedStep> steps,
      List<CapturedConnection> connections,
      List<CapturedSequenceGroup> sequenceGroups,
      List<CapturedConditionGroup> conditionGroups,
      List<CapturedSplitGroup> splitGroups,
      List<CapturedLoopGroup> loopGroups,
      List<CapturedRetryGroup> retryGroups,
      List<CapturedErrorScopeGroup> errorScopeGroups,
      List<CapturedTransfer> transfers,
      List<CapturedRule> rules,
      List<CapturedDelete> deletes) {
    return new WorkTaskCapture(
        WorkOutcome.PREPARED,
        requirements,
        steps,
        connections,
        sequenceGroups,
        conditionGroups,
        splitGroups,
        loopGroups,
        retryGroups,
        errorScopeGroups,
        transfers,
        rules,
        List.of(),
        deletes,
        "",
        "",
        List.of(),
        "",
        "",
        List.of(),
        "");
  }
}

record CapturedRequirement(
    String existingId, String alias, String text, List<String> sourceRefs, String supersededRef) {}

record CapturedStep(
    String existingId,
    String alias,
    StepKind kind,
    String label,
    String intent,
    List<String> sourceRefs,
    List<String> requirementRefs) {}

record CapturedConnection(
    String existingId,
    String alias,
    String sourceStepRef,
    String outcome,
    String targetStepRef,
    String routingIntent,
    List<String> evidenceRefs) {}

record CapturedSequenceGroup(String existingId, String alias, List<String> memberStepRefs) {}

record CapturedConditionGroup(
    String existingId,
    String alias,
    String ownerStepRef,
    List<CapturedConditionBranch> branches,
    String reconvergenceStepRef) {}

record CapturedConditionBranch(
    String existingId,
    String alias,
    ConditionBranchRole role,
    String predicate,
    int priority,
    String entryStepRef,
    List<String> exitStepRefs) {}

record CapturedSplitGroup(
    String existingId,
    String alias,
    String ownerStepRef,
    SplitMode mode,
    List<CapturedSplitBranch> branches,
    String reconvergenceStepRef) {}

record CapturedSplitBranch(
    String existingId, String alias, int order, String entryStepRef, List<String> exitStepRefs) {}

record CapturedLoopGroup(
    String existingId,
    String alias,
    String ownerStepRef,
    String bodyEntryStepRef,
    List<String> bodyExitStepRefs,
    String exitStepRef,
    LoopMode loopMode,
    String loopExpression,
    int loopSafetyBound) {}

record CapturedRetryGroup(
    String existingId,
    String alias,
    String ownerStepRef,
    String bodyEntryStepRef,
    List<String> bodyExitStepRefs,
    String exhaustedStepRef,
    int retryCount,
    int retryDelayMillis) {}

record CapturedErrorScopeGroup(
    String existingId,
    String alias,
    String ownerStepRef,
    String tryEntryStepRef,
    List<CapturedErrorHandler> handlers,
    String finallyEntryStepRef,
    List<String> exitStepRefs) {}

record CapturedErrorHandler(
    String existingId,
    String alias,
    String exceptionClass,
    String entryStepRef,
    List<String> exitStepRefs) {}

record CapturedTransfer(
    String existingId,
    String alias,
    String targetStepRef,
    List<PortRef> sourcePorts,
    PortRef targetPort,
    List<String> requirementRefs,
    String decision,
    List<String> evidenceRefs) {

  public CapturedTransfer {
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
  }

  public CapturedTransfer(
      String existingId,
      String alias,
      String targetStepRef,
      List<PortRef> sourcePorts,
      PortRef targetPort,
      List<String> requirementRefs,
      String decision) {
    this(existingId, alias, targetStepRef, sourcePorts, targetPort, requirementRefs, decision, List.of());
  }
}

record CapturedRule(
    String existingId,
    String alias,
    String transferRef,
    List<FieldReference> sources,
    FieldReference target,
    List<JsonConstant> constants,
    String behavior,
    List<String> evidenceRefs) {}

record CapturedRetainedValue(
    String existingId,
    String alias,
    String stepRef,
    FieldReference source,
    String intendedUse,
    List<String> evidenceRefs) {}

record CapturedDelete(String existingId, List<String> evidenceRefs) {}
