package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Authoritative partial design. The artifact reference lives outside this payload. */
public record ChainWorkDocument(
    int schemaVersion,
    String documentId,
    List<WorkSource> sources,
    List<WorkRequirement> requirements,
    LogicalFlow flow,
    WorkProgress progress) {

  public static final int SCHEMA_VERSION = 2;

  public ChainWorkDocument {
    sources = Lists.copy(sources);
    requirements = Lists.copy(requirements);
    flow = flow == null ? LogicalFlow.empty() : flow;
    progress = progress == null ? WorkProgress.empty() : progress;
  }

  public boolean retainedDependencySatisfied(String retainedValueId) {
    for (LogicalStep step : flow.steps()) {
      for (RetainedValue value : step.data().retainedValues()) {
        if (retainedValueId.equals(value.id())) {
          return value.satisfiesConsumer();
        }
      }
    }
    return false;
  }
}

record WorkSource(
    String id,
    String role,
    String contentReference,
    String contentHash,
    String originalName,
    String suppliedIdentifier,
    List<String> correctionOf,
    String content,
    List<SourcePassage> passages) {

  public WorkSource {
    correctionOf = Lists.copy(correctionOf);
    passages = Lists.copy(passages);
    content = content == null ? "" : content;
  }

  public WorkSource(
      String id,
      String role,
      String contentReference,
      String contentHash,
      String originalName,
      String suppliedIdentifier,
      List<String> correctionOf) {
    this(id, role, contentReference, contentHash, originalName, suppliedIdentifier, correctionOf, "", List.of());
  }
}

record WorkRequirement(
    String id, String text, List<String> sourceIds, String supersededRequirementId) {

  public WorkRequirement {
    sourceIds = Lists.copy(sourceIds);
    supersededRequirementId = supersededRequirementId == null ? "" : supersededRequirementId;
  }
}

record LogicalFlow(
    List<LogicalStep> steps,
    List<LogicalConnection> connections,
    List<SequenceGroup> sequenceGroups,
    List<ConditionGroup> conditionGroups,
    List<SplitGroup> splitGroups,
    List<LoopGroup> loopGroups,
    List<RetryGroup> retryGroups,
    List<ErrorScopeGroup> errorScopeGroups) {

  public LogicalFlow {
    steps = Lists.copy(steps);
    connections = Lists.copy(connections);
    sequenceGroups = Lists.copy(sequenceGroups);
    conditionGroups = Lists.copy(conditionGroups);
    splitGroups = Lists.copy(splitGroups);
    loopGroups = Lists.copy(loopGroups);
    retryGroups = Lists.copy(retryGroups);
    errorScopeGroups = Lists.copy(errorScopeGroups);
  }

  static LogicalFlow empty() {
    return new LogicalFlow(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }
}

record LogicalStep(
    String id,
    StepKind kind,
    String label,
    String intent,
    List<String> sourceIds,
    List<String> requirementIds,
    ResolvedWorkBinding binding,
    StepData data) {

  public LogicalStep {
    sourceIds = Lists.copy(sourceIds);
    requirementIds = Lists.copy(requirementIds);
    data = data == null ? StepData.empty() : data;
  }
}

record StepData(List<DataTransfer> transfers, List<RetainedValue> retainedValues, DataOutline outline) {
  public StepData {
    transfers = Lists.copy(transfers);
    retainedValues = Lists.copy(retainedValues);
    outline = outline == null ? DataOutline.empty() : outline;
  }

  public StepData(List<DataTransfer> transfers, List<RetainedValue> retainedValues) {
    this(transfers, retainedValues, DataOutline.empty());
  }

  static StepData empty() {
    return new StepData(List.of(), List.of());
  }

  StepData withTransfers(List<DataTransfer> nextTransfers) {
    return new StepData(nextTransfers, retainedValues, outline);
  }

  StepData withRetained(List<RetainedValue> nextRetained) {
    return new StepData(transfers, nextRetained, outline);
  }

  StepData withOutline(DataOutline nextOutline) {
    return new StepData(transfers, retainedValues, nextOutline);
  }
}

record DataTransfer(
    String id,
    List<PortRef> sourcePorts,
    PortRef targetPort,
    List<String> requirementIds,
    List<MappingRule> rules,
    MappingDecision decision,
    TransferOutcome outcome,
    List<String> requiredRetainedIds) {

  public DataTransfer {
    sourcePorts = Lists.copy(sourcePorts);
    requirementIds = Lists.copy(requirementIds);
    rules = Lists.copy(rules);
    decision = decision == null ? MappingDecision.UNSPECIFIED : decision;
    outcome = outcome == null ? TransferOutcome.UNSPECIFIED : outcome;
    requiredRetainedIds = Lists.copy(requiredRetainedIds);
  }

  public DataTransfer(
      String id,
      List<PortRef> sourcePorts,
      PortRef targetPort,
      List<String> requirementIds,
      List<MappingRule> rules,
      MappingDecision decision) {
    this(id, sourcePorts, targetPort, requirementIds, rules, decision, TransferOutcome.UNSPECIFIED, List.of());
  }

  public boolean requiredRetainedSatisfied(ChainWorkDocument document) {
    for (String retainedId : requiredRetainedIds) {
      if (!document.retainedDependencySatisfied(retainedId)) {
        return false;
      }
    }
    return true;
  }

  DataTransfer withRules(List<MappingRule> nextRules) {
    return new DataTransfer(
        id, sourcePorts, targetPort, requirementIds, nextRules, decision, outcome, requiredRetainedIds);
  }
}

record MappingRule(
    String id,
    List<FieldReference> sources,
    FieldReference target,
    List<JsonConstant> constants,
    String behavior,
    List<String> evidenceIds) {

  public MappingRule {
    sources = Lists.copy(sources);
    constants = Lists.copy(constants);
    evidenceIds = Lists.copy(evidenceIds);
    behavior = behavior == null ? "" : behavior;
  }
}

record RetainedValue(
    String id,
    FieldReference source,
    String intendedUse,
    List<String> evidenceIds,
    String producerStepId,
    RetainedResolution resolution) {

  public RetainedValue {
    evidenceIds = Lists.copy(evidenceIds);
    intendedUse = intendedUse == null ? "" : intendedUse;
    producerStepId = producerStepId == null ? "" : producerStepId;
    if (resolution == null) {
      resolution = sourceFieldResolved(source) ? RetainedResolution.RESOLVED : RetainedResolution.UNRESOLVED;
    }
    if (resolution == RetainedResolution.RESOLVED && !sourceFieldResolved(source)) {
      throw new IllegalArgumentException(
          "A resolved retained value needs a source field path. Leave the source empty until the field is known.");
    }
    if (resolution == RetainedResolution.UNRESOLVED && sourceFieldResolved(source)) {
      throw new IllegalArgumentException(
          "An unresolved retained value cannot carry a source field path. Clear the path or mark the value resolved.");
    }
  }

  public RetainedValue(String id, FieldReference source, String intendedUse, List<String> evidenceIds) {
    this(id, source, intendedUse, evidenceIds, "", null);
  }

  public boolean satisfiesConsumer() {
    return resolution == RetainedResolution.RESOLVED;
  }

  private static boolean sourceFieldResolved(FieldReference source) {
    return source != null
        && source.kind() == FieldReferenceKind.STEP_PORT
        && source.fieldPath() != null
        && !source.fieldPath().isBlank();
  }
}

record PortRef(String stepId, String portName) {}

record JsonConstant(String name, JsonNode value) {}

record FieldReference(
    FieldReferenceKind kind,
    String stepId,
    PortRole port,
    String fieldPath,
    String retainedValueId) {

  public FieldReference {
    stepId = stepId == null ? "" : stepId;
    fieldPath = fieldPath == null ? "" : fieldPath;
    retainedValueId = retainedValueId == null ? "" : retainedValueId;
  }

  public static FieldReference payload(String stepId, PortRole port, String fieldPath) {
    return new FieldReference(FieldReferenceKind.STEP_PORT, stepId, port, fieldPath, "");
  }
}

record LogicalConnection(
    String id,
    String sourceStepId,
    String outcome,
    String targetStepId,
    String routingIntent,
    List<String> evidenceIds) {

  public LogicalConnection {
    evidenceIds = Lists.copy(evidenceIds);
  }
}

record SequenceGroup(String id, List<String> memberStepIds) {
  public SequenceGroup {
    memberStepIds = Lists.copy(memberStepIds);
  }
}

record ConditionGroup(
    String id, String ownerStepId, List<ConditionBranch> branches, String reconvergenceStepId) {

  public ConditionGroup {
    branches = Lists.copy(branches);
    reconvergenceStepId = reconvergenceStepId == null ? "" : reconvergenceStepId;
  }
}

record ConditionBranch(
    String id,
    org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole role,
    String predicate,
    int priority,
    String entryStepId,
    List<String> exitStepIds) {

  public ConditionBranch {
    exitStepIds = Lists.copy(exitStepIds);
    predicate = predicate == null ? "" : predicate;
  }
}

record SplitGroup(
    String id,
    String ownerStepId,
    org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode mode,
    List<SplitBranch> branches,
    String reconvergenceStepId) {

  public SplitGroup {
    branches = Lists.copy(branches);
    reconvergenceStepId = reconvergenceStepId == null ? "" : reconvergenceStepId;
  }
}

record SplitBranch(
    String id, int order, String entryStepId, List<String> exitStepIds) {

  public SplitBranch {
    exitStepIds = Lists.copy(exitStepIds);
  }
}

record LoopGroup(
    String id,
    String ownerStepId,
    String bodyEntryStepId,
    List<String> bodyExitStepIds,
    String exitStepId,
    org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode loopMode,
    String loopExpression,
    int loopSafetyBound) {

  public LoopGroup {
    bodyExitStepIds = Lists.copy(bodyExitStepIds);
    loopExpression = loopExpression == null ? "" : loopExpression;
  }
}

record RetryGroup(
    String id,
    String ownerStepId,
    String bodyEntryStepId,
    List<String> bodyExitStepIds,
    String exhaustedStepId,
    int retryCount,
    int retryDelayMillis) {

  public RetryGroup {
    bodyExitStepIds = Lists.copy(bodyExitStepIds);
  }
}

record ErrorScopeGroup(
    String id,
    String ownerStepId,
    String tryEntryStepId,
    List<ErrorHandler> handlers,
    String finallyEntryStepId,
    List<String> exitStepIds) {

  public ErrorScopeGroup {
    handlers = Lists.copy(handlers);
    exitStepIds = Lists.copy(exitStepIds);
    finallyEntryStepId = finallyEntryStepId == null ? "" : finallyEntryStepId;
  }
}

record ErrorHandler(
    String id, String exceptionClass, String entryStepId, List<String> exitStepIds) {

  public ErrorHandler {
    exitStepIds = Lists.copy(exitStepIds);
  }
}

record WorkProgress(
    List<WorkTaskRecord> tasks,
    List<WorkFinding> findings,
    List<WorkQuestion> questions,
    String approvalReference,
    List<String> derivedResultReferences,
    List<String> recheckStages) {

  public WorkProgress {
    tasks = Lists.copy(tasks);
    findings = Lists.copy(findings);
    questions = Lists.copy(questions);
    derivedResultReferences = Lists.copy(derivedResultReferences);
    recheckStages = Lists.copy(recheckStages);
    approvalReference = approvalReference == null ? "" : approvalReference;
  }

  static WorkProgress empty() {
    return new WorkProgress(List.of(), List.of(), List.of(), "", List.of(), List.of());
  }
}

record WorkTaskRecord(
    String taskKey,
    WorkTaskKind kind,
    String taskId,
    WorkTaskState state,
    WorkStage stage,
    String skillId,
    String acceptedInputFingerprint,
    List<String> producedRecordIds) {

  public WorkTaskRecord {
    kind = kind == null ? WorkTaskKind.UNSPECIFIED : kind;
    taskKey = taskKey == null || taskKey.isBlank() ? taskId : taskKey;
    acceptedInputFingerprint = acceptedInputFingerprint == null ? "" : acceptedInputFingerprint;
    producedRecordIds = Lists.copy(producedRecordIds);
  }

  public WorkTaskRecord(String taskId, WorkTaskState state, WorkStage stage, String skillId) {
    this(taskId, WorkTaskKind.UNSPECIFIED, taskId, state, stage, skillId, "", List.of());
  }
}

record WorkFinding(
    String id,
    String recordRef,
    String issueCategory,
    String contradiction,
    List<String> evidenceIds,
    String canonicalFieldPointer) {

  public WorkFinding(
      String id,
      String recordRef,
      String issueCategory,
      String contradiction,
      List<String> evidenceIds) {
    this(id, recordRef, issueCategory, contradiction, evidenceIds, "");
  }

  public WorkFinding {
    evidenceIds = Lists.copy(evidenceIds);
    canonicalFieldPointer = canonicalFieldPointer == null ? "" : canonicalFieldPointer;
  }
}

record WorkQuestion(
    String id,
    String choice,
    String question,
    List<String> evidenceIds,
    String ownerTaskKey,
    QuestionSubject subject,
    List<String> blockedRecordIds,
    List<String> answerSourceIds,
    QuestionResolution resolution) {

  public WorkQuestion {
    evidenceIds = Lists.copy(evidenceIds);
    choice = choice == null ? "" : choice;
    question = question == null ? "" : question;
    ownerTaskKey = ownerTaskKey == null ? "" : ownerTaskKey;
    subject = subject == null ? QuestionSubject.unspecified() : subject;
    blockedRecordIds = Lists.copy(blockedRecordIds);
    answerSourceIds = Lists.copy(answerSourceIds);
    resolution = resolution == null ? QuestionResolution.OPEN : resolution;
  }

  public WorkQuestion(String id, String choice, String question, List<String> evidenceIds) {
    this(id, choice, question, evidenceIds, "", null, List.of(), List.of(), QuestionResolution.OPEN);
  }
}

final class Lists {
  private Lists() {}

  static <T> List<T> copy(List<T> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return List.copyOf(values);
  }

  static <T> List<T> mutable(List<T> values) {
    return new ArrayList<>(values == null ? List.of() : values);
  }
}
