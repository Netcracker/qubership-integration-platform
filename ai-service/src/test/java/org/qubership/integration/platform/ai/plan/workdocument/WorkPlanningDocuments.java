package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;

/** Document builders for planner tests. This type does not seed task definitions. */
final class WorkPlanningDocuments {

  static final String SOURCE_ID = "source-1";
  static final String OTHER_SOURCE_ID = "source-2";
  static final String PASSAGE_ID = "passage-1";
  static final String OTHER_PASSAGE_ID = "passage-2";
  static final String REQUIREMENT_ID = "req-1";
  static final String OTHER_REQUIREMENT_ID = "req-2";

  private WorkPlanningDocuments() {}

  static ChainWorkDocument document(
      String documentId,
      List<LogicalStep> steps,
      List<LogicalConnection> connections,
      WorkProgress progress) {
    return document(documentId, steps, connections, List.of(), List.of(), List.of(), progress);
  }

  static ChainWorkDocument document(
      String documentId,
      List<LogicalStep> steps,
      List<LogicalConnection> connections,
      List<ConditionGroup> conditions,
      List<LoopGroup> loops,
      List<RetryGroup> retries,
      WorkProgress progress) {
    return new ChainWorkDocument(
        ChainWorkDocument.SCHEMA_VERSION,
        documentId,
        List.of(source(SOURCE_ID, "hash-source-1", PASSAGE_ID, "Map the order.")),
        List.of(new WorkRequirement(REQUIREMENT_ID, "Map the order", List.of(SOURCE_ID), "")),
        new LogicalFlow(
            steps,
            connections,
            List.of(),
            conditions,
            List.of(),
            loops,
            retries,
            List.of()),
        progress == null ? WorkProgress.empty() : progress);
  }

  static ChainWorkDocument withSources(ChainWorkDocument document, List<WorkSource> sources, List<WorkRequirement> requirements) {
    return new ChainWorkDocument(
        document.schemaVersion(),
        document.documentId(),
        sources,
        requirements,
        document.flow(),
        document.progress());
  }

  static WorkSource source(String id, String contentHash, String passageId, String text) {
    return new WorkSource(
        id,
        "request",
        "artifact://" + id,
        contentHash,
        id + ".md",
        "REQ",
        List.of(),
        text,
        List.of(new SourcePassage(passageId, id, "hash-" + passageId, text, "")));
  }

  static LogicalStep step(
      String id, StepKind kind, ResolvedWorkBinding binding, StepData data, String... requirementIds) {
    return new LogicalStep(
        id,
        kind,
        "label-" + id,
        "intent-" + id,
        List.of(SOURCE_ID),
        List.of(requirementIds),
        binding,
        data == null ? StepData.empty() : data);
  }

  static ResolvedWorkBinding binding(String operationId, String version) {
    return new ResolvedWorkBinding(
        "catalog-" + operationId,
        version,
        operationId,
        "http",
        "POST",
        "/" + operationId,
        List.of("contract-" + operationId),
        List.of("request", "success"));
  }

  static LogicalConnection connection(String id, String sourceStepId, String outcome, String targetStepId) {
    return new LogicalConnection(id, sourceStepId, outcome, targetStepId, "route", List.of(SOURCE_ID));
  }

  static DataOutline covered(String requirementId, String passageId, String transferId, CoverageDisposition disposition) {
    return new DataOutline(
        List.of(requirementId),
        transferId == null || transferId.isBlank() ? List.of() : List.of(transferId),
        List.of(new CoverageEntry(requirementId, passageId, disposition)));
  }

  static DataTransfer transfer(
      String id,
      String sourceStepId,
      String targetStepId,
      String port,
      TransferOutcome outcome,
      MappingDecision decision,
      List<MappingRule> rules,
      List<String> retainedIds,
      String... requirementIds) {
    return new DataTransfer(
        id,
        List.of(new PortRef(sourceStepId, "payload")),
        new PortRef(targetStepId, port),
        List.of(requirementIds),
        rules,
        decision,
        outcome,
        retainedIds);
  }

  static MappingRule rule(String id, String sourceStepId, String targetStepId) {
    return new MappingRule(
        id,
        List.of(FieldReference.payload(sourceStepId, PortRole.INBOUND_PAYLOAD, "$.priority")),
        FieldReference.payload(targetStepId, PortRole.OUTBOUND_REQUEST, "$.Priority"),
        List.of(),
        "Map high to High.",
        List.of(SOURCE_ID));
  }

  static RetainedValue unresolved(String id, String producerStepId) {
    return new RetainedValue(id, null, "Order id for the call", List.of(PASSAGE_ID), producerStepId, RetainedResolution.UNRESOLVED);
  }

  static RetainedValue resolved(String id, String producerStepId) {
    return new RetainedValue(
        id,
        FieldReference.payload(producerStepId, PortRole.INBOUND_PAYLOAD, "$.orderId"),
        "Order id for the call",
        List.of(PASSAGE_ID),
        producerStepId,
        RetainedResolution.RESOLVED);
  }

  static LoopGroup loop(String id, String ownerStepId, String bodyStepId, String exitStepId) {
    return new LoopGroup(id, ownerStepId, bodyStepId, List.of(bodyStepId), exitStepId, LoopMode.DO_WHILE, "items", 10);
  }

  static RetryGroup retry(String id, String ownerStepId, String bodyStepId, String exhaustedStepId) {
    return new RetryGroup(id, ownerStepId, bodyStepId, List.of(bodyStepId), exhaustedStepId, 3, 1000);
  }

  static ChainWorkDocument accept(ChainWorkDocument document, Predicate<WorkTaskPlanner.Task> include) {
    WorkTaskPlanner.Plan plan = new WorkTaskPlanner().plan(document);
    List<WorkTaskRecord> tasks = new ArrayList<>();
    for (WorkTaskRecord existing : document.progress().tasks()) {
      if (plan.tasks().stream().noneMatch(task -> task.taskKey().equals(existing.taskKey()) && include.test(task))) {
        tasks.add(existing);
      }
    }
    for (WorkTaskPlanner.Task task : plan.tasks()) {
      if (!include.test(task)) {
        continue;
      }
      tasks.add(
          new WorkTaskRecord(
              task.taskKey(),
              task.kind(),
              task.taskId(),
              WorkTaskState.ACCEPTED,
              task.stage(),
              task.skillId(),
              task.requiredInputFingerprint(),
              task.assignedRecordIds()));
    }
    return replaceProgress(
        document,
        new WorkProgress(
            tasks,
            document.progress().findings(),
            document.progress().questions(),
            document.progress().approvalReference(),
            document.progress().derivedResultReferences(),
            document.progress().recheckStages()));
  }

  static ChainWorkDocument replaceProgress(ChainWorkDocument document, WorkProgress progress) {
    return new ChainWorkDocument(
        document.schemaVersion(),
        document.documentId(),
        document.sources(),
        document.requirements(),
        document.flow(),
        progress);
  }

  static ChainWorkDocument relabel(ChainWorkDocument document, String stepId, String label) {
    List<LogicalStep> steps = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      if (step.id().equals(stepId)) {
        steps.add(
            new LogicalStep(
                step.id(),
                step.kind(),
                label,
                step.intent(),
                step.sourceIds(),
                step.requirementIds(),
                step.binding(),
                step.data()));
      } else {
        steps.add(step);
      }
    }
    return replaceFlow(document, steps, document.flow().connections());
  }

  static ChainWorkDocument withErrorScopes(ChainWorkDocument document, List<ErrorScopeGroup> groups) {
    LogicalFlow prior = document.flow();
    return new ChainWorkDocument(
        document.schemaVersion(),
        document.documentId(),
        document.sources(),
        document.requirements(),
        new LogicalFlow(
            prior.steps(),
            prior.connections(),
            prior.sequenceGroups(),
            prior.conditionGroups(),
            prior.splitGroups(),
            prior.loopGroups(),
            prior.retryGroups(),
            groups),
        document.progress());
  }

  static ErrorScopeGroup errorScope(
      String id, String ownerStepId, String tryEntryStepId, String handlerEntryStepId, String exitStepId) {
    return new ErrorScopeGroup(
        id,
        ownerStepId,
        tryEntryStepId,
        List.of(new ErrorHandler("handler-1", "Exception", handlerEntryStepId, List.of(handlerEntryStepId))),
        "",
        List.of(exitStepId));
  }

  static ChainWorkDocument replaceFlow(
      ChainWorkDocument document, List<LogicalStep> steps, List<LogicalConnection> connections) {
    LogicalFlow prior = document.flow();
    return new ChainWorkDocument(
        document.schemaVersion(),
        document.documentId(),
        document.sources(),
        document.requirements(),
        new LogicalFlow(
            steps,
            connections,
            prior.sequenceGroups(),
            prior.conditionGroups(),
            prior.splitGroups(),
            prior.loopGroups(),
            prior.retryGroups(),
            prior.errorScopeGroups()),
        document.progress());
  }
}
