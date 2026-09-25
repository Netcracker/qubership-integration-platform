package org.qubership.integration.platform.ai.plan.workdocument;

import org.qubership.integration.platform.ai.plan.workdocument.binding.WorkBinding;
import org.qubership.integration.platform.ai.plan.workdocument.flow.WorkLogicalFlow;
import org.qubership.integration.platform.ai.plan.workdocument.mapping.WorkMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives filling tasks from one committed document and chooses the next ready task.
 * Keys use the task kind and a permanent record id. This type does not call a model.
 */
public final class WorkTaskPlanner {

  public static final String DESCRIBE_CONTEXT_SKILL = "describe-context";

  /** Selection order. Independent tasks of one kind then sort by task key. */
  public static final List<WorkTaskKind> KIND_ORDER =
      List.of(
          WorkTaskKind.LOGICAL_DESIGN,
          WorkTaskKind.SELECT_OPERATION,
          WorkTaskKind.DEFINE_TRANSFERS,
          WorkTaskKind.DESCRIBE_CONTEXT,
          WorkTaskKind.MAP_TRANSFER,
          WorkTaskKind.REPAIR_RULE);

  public WorkTaskPlanner() {}

  public static String taskKey(WorkTaskKind kind, String recordId) {
    return slug(kind) + ":" + recordId;
  }

  public static String taskId(WorkTaskKind kind, String recordId) {
    return slug(kind) + "-" + recordId;
  }

  public Plan plan(ChainWorkDocument document) {
    if (document == null) {
      throw new IllegalArgumentException("A document is required. Plan from the committed work document.");
    }
    Graph graph = Graph.of(document);
    List<Draft> drafts = derive(document, graph);
    Map<String, Draft> byKey = new LinkedHashMap<>();
    Set<String> duplicates = new LinkedHashSet<>();
    for (Draft draft : drafts) {
      if (byKey.containsKey(draft.taskKey)) {
        duplicates.add(draft.taskKey);
      } else {
        byKey.put(draft.taskKey, draft);
      }
    }
    applyProgress(document, byKey);
    Map<String, Block> blocks = new LinkedHashMap<>();
    blockDuplicates(duplicates, blocks);
    blockCycles(graph, byKey, blocks);
    blockReferences(document, graph, byKey, blocks);
    blockCoverage(document, byKey, blocks);
    blockFindings(document, byKey, blocks);
    blockQuestions(document, byKey, blocks);
    markReady(byKey, blocks);
    List<Draft> ordered = new ArrayList<>(byKey.values());
    ordered.sort(WorkTaskPlanner::byKindThenKey);
    Draft running = ordered.stream().filter(draft -> draft.state == WorkTaskState.RUNNING).findFirst().orElse(null);
    Draft selected = null;
    if (running == null) {
      for (Draft draft : ordered) {
        if (draft.ready) {
          selected = draft;
          break;
        }
      }
    }
    List<Block> blocked = new ArrayList<>(blocks.values());
    blocked.sort(WorkTaskPlanner::byBlock);
    List<Block> defects = new ArrayList<>();
    for (Block block : blocked) {
      if (structural(block.reason())) {
        defects.add(block);
      }
    }
    List<String> questionIds = openQuestionIds(document);
    Readiness.Status status = status(selected, running, ordered, defects, questionIds, blocks);
    return new Plan(
        ordered.stream().map(Draft::freeze).toList(),
        selected == null ? null : selected.freeze(),
        blocked,
        new Readiness(status, questionIds, defects));
  }

  private static List<Draft> derive(ChainWorkDocument document, Graph graph) {
    List<Draft> drafts = new ArrayList<>();
    drafts.add(logical(document));
    List<LogicalStep> steps = sortedSteps(document);
    for (LogicalStep step : steps) {
      if (step.kind() != StepKind.LOCAL) {
        drafts.add(binding(document, step));
      }
    }
    for (LogicalStep step : steps) {
      drafts.add(outline(document, graph, step));
    }
    for (Map.Entry<String, List<RetainedValue>> entry : retainedByProducer(document).entrySet()) {
      drafts.add(context(document, graph, entry.getKey(), entry.getValue()));
    }
    for (OwnedTransfer owned : transfers(document)) {
      drafts.add(mapping(document, graph, owned));
    }
    for (Map.Entry<String, List<WorkFinding>> entry : findingsByRule(document).entrySet()) {
      OwnedTransfer owned = transferOfRule(document, entry.getKey());
      if (owned != null) {
        drafts.add(repair(document, owned, entry.getKey(), entry.getValue()));
      }
    }
    return drafts;
  }

  private static Draft logical(ChainWorkDocument document) {
    String recordId = document.documentId() == null ? "" : document.documentId();
    return draft(
        WorkTaskKind.LOGICAL_DESIGN,
        WorkStage.LOGICAL_FLOW,
        WorkLogicalFlow.SKILL_ID,
        recordId,
        List.of(recordId),
        List.of(),
        List.of(),
        fingerprintLogical(document));
  }

  private static Draft binding(ChainWorkDocument document, LogicalStep step) {
    return draft(
        WorkTaskKind.SELECT_OPERATION,
        WorkStage.SERVICES,
        WorkBinding.SKILL_ID,
        step.id(),
        List.of(step.id()),
        List.of(step.id()),
        List.of(taskKey(WorkTaskKind.LOGICAL_DESIGN, documentId(document))),
        fingerprintBinding(step));
  }

  private static Draft outline(ChainWorkDocument document, Graph graph, LogicalStep step) {
    List<String> producers = graph.producers(step.id());
    List<String> dependencies = new ArrayList<>();
    dependencies.add(taskKey(WorkTaskKind.LOGICAL_DESIGN, documentId(document)));
    if (step.kind() != StepKind.LOCAL) {
      dependencies.add(taskKey(WorkTaskKind.SELECT_OPERATION, step.id()));
    }
    for (String producer : producers) {
      LogicalStep owner = graph.step(producer);
      if (owner != null && owner.kind() != StepKind.LOCAL) {
        dependencies.add(taskKey(WorkTaskKind.SELECT_OPERATION, producer));
      }
    }
    return draft(
        WorkTaskKind.DEFINE_TRANSFERS,
        WorkStage.DATA_BEHAVIOR,
        WorkDataOutline.SKILL_ID,
        step.id(),
        List.of(step.id()),
        List.of(step.id()),
        dependencies,
        fingerprintOutline(document, graph, step, producers));
  }

  private static Draft context(
      ChainWorkDocument document, Graph graph, String producerId, List<RetainedValue> values) {
    List<String> retainedIds = new ArrayList<>();
    for (RetainedValue value : values) {
      retainedIds.add(value.id());
    }
    Collections.sort(retainedIds);
    List<String> assigned = new ArrayList<>();
    assigned.add(producerId);
    assigned.addAll(retainedIds);
    List<String> dependencies = new ArrayList<>();
    dependencies.add(taskKey(WorkTaskKind.LOGICAL_DESIGN, documentId(document)));
    LogicalStep producer = graph.step(producerId);
    if (producer != null && producer.kind() != StepKind.LOCAL) {
      dependencies.add(taskKey(WorkTaskKind.SELECT_OPERATION, producerId));
    }
    for (String targetId : targetsReferencing(document, retainedIds)) {
      dependencies.add(taskKey(WorkTaskKind.DEFINE_TRANSFERS, targetId));
    }
    return draft(
        WorkTaskKind.DESCRIBE_CONTEXT,
        WorkStage.DATA_BEHAVIOR,
        DESCRIBE_CONTEXT_SKILL,
        producerId,
        assigned,
        List.of(producerId),
        dependencies,
        fingerprintContext(graph, producerId, values));
  }

  private static Draft mapping(ChainWorkDocument document, Graph graph, OwnedTransfer owned) {
    DataTransfer transfer = owned.transfer();
    List<String> assigned = new ArrayList<>();
    assigned.add(owned.stepId());
    assigned.add(transfer.id());
    if (transfer.targetPort() != null) {
      assigned.add(transfer.targetPort().stepId());
    }
    List<String> scope = new ArrayList<>(assigned);
    for (PortRef source : transfer.sourcePorts()) {
      scope.add(source.stepId());
    }
    for (String retainedId : transfer.requiredRetainedIds()) {
      RetainedValue value = graph.retained(retainedId);
      if (value != null && !value.producerStepId().isBlank()) {
        scope.add(value.producerStepId());
      }
    }
    List<String> dependencies = new ArrayList<>();
    dependencies.add(taskKey(WorkTaskKind.LOGICAL_DESIGN, documentId(document)));
    dependencies.add(taskKey(WorkTaskKind.DEFINE_TRANSFERS, owned.stepId()));
    addBindingDependency(graph, dependencies, owned.stepId());
    for (PortRef source : transfer.sourcePorts()) {
      addBindingDependency(graph, dependencies, source.stepId());
    }
    if (transfer.targetPort() != null) {
      addBindingDependency(graph, dependencies, transfer.targetPort().stepId());
    }
    for (String retainedId : transfer.requiredRetainedIds()) {
      RetainedValue value = graph.retained(retainedId);
      if (value == null || value.satisfiesConsumer()) {
        continue;
      }
      String producer = producerId(value, graph);
      if (!producer.isBlank()) {
        dependencies.add(taskKey(WorkTaskKind.DESCRIBE_CONTEXT, producer));
      }
    }
    return draft(
        WorkTaskKind.MAP_TRANSFER,
        WorkStage.DATA_BEHAVIOR,
        WorkMapping.SKILL_ID,
        transfer.id(),
        assigned,
        scope,
        dependencies,
        fingerprintMapping(document, graph, owned));
  }

  private static Draft repair(
      ChainWorkDocument document, OwnedTransfer owned, String ruleId, List<WorkFinding> findings) {
    List<String> dependencies = new ArrayList<>();
    dependencies.add(taskKey(WorkTaskKind.LOGICAL_DESIGN, documentId(document)));
    dependencies.add(taskKey(WorkTaskKind.MAP_TRANSFER, owned.transfer().id()));
    return draft(
        WorkTaskKind.REPAIR_RULE,
        WorkStage.DATA_BEHAVIOR,
        WorkMapping.SKILL_ID,
        ruleId,
        List.of(ruleId, owned.transfer().id()),
        List.of(owned.stepId(), ruleId),
        dependencies,
        fingerprintRepair(owned, ruleId, findings));
  }

  private static void addBindingDependency(Graph graph, List<String> dependencies, String stepId) {
    LogicalStep step = graph.step(stepId);
    if (step != null && step.kind() != StepKind.LOCAL) {
      dependencies.add(taskKey(WorkTaskKind.SELECT_OPERATION, stepId));
    }
  }

  private static void applyProgress(ChainWorkDocument document, Map<String, Draft> byKey) {
    Map<String, WorkTaskRecord> stored = new LinkedHashMap<>();
    for (WorkTaskRecord task : document.progress().tasks()) {
      stored.put(task.taskKey(), task);
    }
    for (Draft draft : byKey.values()) {
      WorkTaskRecord record = stored.get(draft.taskKey);
      if (record == null) {
        draft.state = WorkTaskState.PENDING;
      } else if (record.state() == WorkTaskState.ACCEPTED
          && !draft.fingerprint.equals(record.acceptedInputFingerprint())) {
        draft.state = WorkTaskState.NEEDS_RECHECK;
      } else {
        draft.state = record.state();
      }
    }
  }

  private static void blockDuplicates(Set<String> duplicates, Map<String, Block> blocks) {
    for (String taskKey : duplicates) {
      put(blocks, new Block(taskKey, Reason.DUPLICATE_KEY, "Task " + taskKey + " is derived more than once."));
    }
  }

  private static void blockCycles(Graph graph, Map<String, Draft> byKey, Map<String, Block> blocks) {
    if (graph.cycleSteps.isEmpty()) {
      return;
    }
    String evidence = cycleEvidence(graph.cycleSteps);
    for (Draft draft : byKey.values()) {
      if (intersects(draft.scopeIds, graph.cycleSteps)) {
        put(blocks, new Block(draft.taskKey, Reason.CYCLE, evidence));
      }
    }
  }

  private static void blockReferences(
      ChainWorkDocument document, Graph graph, Map<String, Draft> byKey, Map<String, Block> blocks) {
    for (Draft draft : byKey.values()) {
      if (draft.kind == WorkTaskKind.SELECT_OPERATION
          && draft.state == WorkTaskState.ACCEPTED
          && graph.step(draft.recordId) != null
          && graph.step(draft.recordId).binding() == null) {
        put(
            blocks,
            new Block(
                draft.taskKey,
                Reason.MISSING_INPUT,
                "Step " + draft.recordId + " has no pinned contract."));
      }
    }
    for (OwnedTransfer owned : transfers(document)) {
      Draft mapping = byKey.get(taskKey(WorkTaskKind.MAP_TRANSFER, owned.transfer().id()));
      if (mapping == null) {
        continue;
      }
      String invalid = invalidTransfer(graph, owned);
      if (invalid != null) {
        put(blocks, new Block(mapping.taskKey, Reason.INVALID_REFERENCE, invalid));
        continue;
      }
      for (String retainedId : owned.transfer().requiredRetainedIds()) {
        RetainedValue value = graph.retained(retainedId);
        if (value == null) {
          put(
              blocks,
              new Block(
                  mapping.taskKey,
                  Reason.MISSING_INPUT,
                  "Transfer "
                      + owned.transfer().id()
                      + " requires retained value "
                      + retainedId
                      + ", and the document has no retained value with that id."));
          continue;
        }
        String producer = producerId(value, graph);
        if (!graph.available(producer, owned.stepId())) {
          put(
              blocks,
              new Block(
                  mapping.taskKey,
                  Reason.UNAVAILABLE_INPUT,
                  "Retained value "
                      + retainedId
                      + " is produced on step "
                      + producer
                      + " and is not available at step "
                      + owned.stepId()
                      + "."));
          continue;
        }
        if (!value.satisfiesConsumer() && contextCurrent(byKey, producer)) {
          put(
              blocks,
              new Block(
                  mapping.taskKey,
                  Reason.UNRESOLVED_INPUT,
                  "Transfer "
                      + owned.transfer().id()
                      + " requires retained value "
                      + retainedId
                      + ", and that value is unresolved."));
        }
      }
      if (mapping.state == WorkTaskState.ACCEPTED
          && owned.transfer().rules().isEmpty()
          && owned.transfer().decision() != MappingDecision.NO_MAPPING) {
        put(
            blocks,
            new Block(
                mapping.taskKey,
                Reason.UNEVIDENCED_MAPPING,
                "Transfer "
                    + owned.transfer().id()
                    + " has no rules and no NO_MAPPING decision."));
      }
    }
  }

  private static boolean contextCurrent(Map<String, Draft> byKey, String producerId) {
    Draft context = byKey.get(taskKey(WorkTaskKind.DESCRIBE_CONTEXT, producerId));
    return context != null && context.state == WorkTaskState.ACCEPTED;
  }

  private static String invalidTransfer(Graph graph, OwnedTransfer owned) {
    if (graph.step(owned.stepId()) == null) {
      return "Transfer " + owned.transfer().id() + " is stored on step " + owned.stepId()
          + ", and the document has no step with that id.";
    }
    for (PortRef source : owned.transfer().sourcePorts()) {
      if (graph.step(source.stepId()) == null) {
        return "Transfer "
            + owned.transfer().id()
            + " reads port "
            + source.portName()
            + " on step "
            + source.stepId()
            + ", and the document has no step with that id.";
      }
    }
    PortRef target = owned.transfer().targetPort();
    if (target == null || graph.step(target.stepId()) == null) {
      String stepId = target == null ? "" : target.stepId();
      return "Transfer " + owned.transfer().id() + " targets step " + stepId
          + ", and the document has no step with that id.";
    }
    if (!owned.stepId().equals(target.stepId())) {
      return "Transfer "
          + owned.transfer().id()
          + " targets step "
          + target.stepId()
          + " but is stored on step "
          + owned.stepId()
          + ".";
    }
    return null;
  }

  private static void blockCoverage(ChainWorkDocument document, Map<String, Draft> byKey, Map<String, Block> blocks) {
    for (LogicalStep step : document.flow().steps()) {
      Draft outline = byKey.get(taskKey(WorkTaskKind.DEFINE_TRANSFERS, step.id()));
      if (outline == null || outline.state != WorkTaskState.ACCEPTED) {
        continue;
      }
      List<WorkRequirement> relevant = relevant(document, step);
      for (WorkRequirement requirement : relevant) {
        if (coverageFor(step, requirement.id()).isEmpty()) {
          put(
              blocks,
              new Block(
                  outline.taskKey,
                  Reason.COVERAGE_GAP,
                  "Step " + step.id() + " is missing coverage for requirement " + requirement.id() + "."));
        }
      }
      for (CoverageEntry entry : step.data().outline().coverage()) {
        boolean listed = listedOnTransfer(step, entry.requirementId());
        if (entry.disposition() == CoverageDisposition.ASSIGNED && !listed) {
          put(
              blocks,
              new Block(
                  outline.taskKey,
                  Reason.COVERAGE_GAP,
                  "Step "
                      + step.id()
                      + " assigns requirement "
                      + entry.requirementId()
                      + " without a transfer."));
        }
        if ((entry.disposition() == CoverageDisposition.NO_MAPPING || entry.disposition() == CoverageDisposition.QUESTION)
            && listed) {
          put(
              blocks,
              new Block(
                  outline.taskKey,
                  Reason.COVERAGE_GAP,
                  "Step "
                      + step.id()
                      + " records requirement "
                      + entry.requirementId()
                      + " as "
                      + entry.disposition()
                      + " and also lists it on a transfer."));
        }
        if (entry.disposition() == CoverageDisposition.QUESTION) {
          put(
              blocks,
              new Block(
                  outline.taskKey,
                  Reason.COVERAGE_GAP,
                  "Step "
                      + step.id()
                      + " records requirement "
                      + entry.requirementId()
                      + " as QUESTION coverage. That is not accepted coverage."));
        }
      }
    }
  }

  private static void blockFindings(ChainWorkDocument document, Map<String, Draft> byKey, Map<String, Block> blocks) {
    for (WorkFinding finding : document.progress().findings()) {
      if (rule(document, finding.recordRef()) != null) {
        continue;
      }
      Block block =
          new Block(
              finding.id(),
              Reason.ACTIVE_FINDING,
              "Finding " + finding.id() + " records an open defect on " + finding.recordRef() + ".");
      boolean attached = false;
      for (Draft draft : byKey.values()) {
        if (contains(draft.assignedRecordIds, finding.recordRef()) || contains(draft.scopeIds, finding.recordRef())) {
          put(blocks, new Block(draft.taskKey, block.reason(), block.evidence()));
          attached = true;
        }
      }
      if (!attached) {
        put(blocks, block);
      }
    }
  }

  private static void blockQuestions(ChainWorkDocument document, Map<String, Draft> byKey, Map<String, Block> blocks) {
    List<WorkQuestion> questions = new ArrayList<>(document.progress().questions());
    questions.sort((left, right) -> left.id().compareTo(right.id()));
    for (WorkQuestion question : questions) {
      if (question.resolution() == QuestionResolution.ANSWERED) {
        Draft owner = byKey.get(question.ownerTaskKey());
        if (owner != null && owner.state == WorkTaskState.ACCEPTED) {
          continue;
        }
        for (Draft draft : byKey.values()) {
          if (draft.taskKey.equals(question.ownerTaskKey())) {
            continue;
          }
          if (intersects(draft.assignedRecordIds, question.blockedRecordIds())) {
            put(blocks, questionBlock(question, draft.taskKey));
          }
        }
        continue;
      }
      if (question.resolution() != QuestionResolution.OPEN) {
        continue;
      }
      for (Draft draft : byKey.values()) {
        boolean owner = draft.taskKey.equals(question.ownerTaskKey());
        boolean blockedRecord = intersects(draft.assignedRecordIds, question.blockedRecordIds());
        if (owner || blockedRecord) {
          put(blocks, questionBlock(question, draft.taskKey));
        }
      }
      if (question.resolution() == QuestionResolution.OPEN && !question.ownerTaskKey().isBlank() && !byKey.containsKey(question.ownerTaskKey())) {
        put(blocks, questionBlock(question, question.ownerTaskKey()));
      }
    }
    for (Draft draft : byKey.values()) {
      if (draft.state == WorkTaskState.NEEDS_INPUT) {
        put(blocks, new Block(draft.taskKey, Reason.WAITING_FOR_INPUT, "Task " + draft.taskKey + " needs input."));
      }
      if (draft.state == WorkTaskState.HALTED) {
        put(blocks, new Block(draft.taskKey, Reason.ACTIVE_FINDING, "Task " + draft.taskKey + " is halted."));
      }
    }
  }

  private static Block questionBlock(WorkQuestion question, String taskKey) {
    return new Block(taskKey, Reason.WAITING_FOR_INPUT, "Question " + question.id() + " blocks " + taskKey + ".");
  }

  private static void markReady(Map<String, Draft> byKey, Map<String, Block> blocks) {
    for (Draft draft : byKey.values()) {
      if (blocks.containsKey(draft.taskKey)) {
        draft.ready = false;
        continue;
      }
      if (draft.state != WorkTaskState.PENDING && draft.state != WorkTaskState.NEEDS_RECHECK) {
        draft.ready = false;
        continue;
      }
      List<String> unmet = unmet(byKey, blocks, draft.dependencyKeys);
      if (unmet.isEmpty()) {
        draft.ready = true;
      } else {
        draft.ready = false;
        put(blocks, new Block(draft.taskKey, Reason.WAITING_FOR_TASK, waits(draft.taskKey, unmet)));
      }
    }
  }

  private static List<String> unmet(Map<String, Draft> byKey, Map<String, Block> blocks, List<String> dependencyKeys) {
    List<String> unmet = new ArrayList<>();
    for (String dependency : dependencyKeys) {
      Draft required = byKey.get(dependency);
      Block block = blocks.get(dependency);
      boolean structurallyBlocked = block != null && structural(block.reason());
      boolean inputBlocked = block != null && block.reason() == Reason.WAITING_FOR_INPUT;
      if (required == null || required.state != WorkTaskState.ACCEPTED || structurallyBlocked || inputBlocked) {
        unmet.add(dependency);
      }
    }
    Collections.sort(unmet);
    return unmet;
  }

  private static String waits(String taskKey, List<String> unmet) {
    if (unmet.size() == 1) {
      return "Task " + taskKey + " waits for " + unmet.get(0) + ".";
    }
    return "Task " + taskKey + " waits for " + String.join(", ", unmet) + ".";
  }

  private static Readiness.Status status(
      Draft selected,
      Draft running,
      List<Draft> tasks,
      List<Block> defects,
      List<String> questionIds,
      Map<String, Block> blocks) {
    if (selected != null || running != null) {
      return Readiness.Status.WORK_REMAINING;
    }
    if (!defects.isEmpty()) {
      return Readiness.Status.HALTED;
    }
    if (!questionIds.isEmpty() || waitingForInput(tasks, blocks)) {
      return Readiness.Status.WAITING_FOR_INPUT;
    }
    for (Draft task : tasks) {
      if (task.state != WorkTaskState.ACCEPTED) {
        return Readiness.Status.HALTED;
      }
    }
    return Readiness.Status.READY_FOR_PRESENTATION;
  }

  private static boolean waitingForInput(List<Draft> tasks, Map<String, Block> blocks) {
    for (Draft task : tasks) {
      if (task.state == WorkTaskState.NEEDS_INPUT) {
        return true;
      }
    }
    for (Block block : blocks.values()) {
      if (block.reason() == Reason.WAITING_FOR_INPUT) {
        return true;
      }
    }
    return false;
  }

  private static List<String> openQuestionIds(ChainWorkDocument document) {
    List<String> ids = new ArrayList<>();
    for (WorkQuestion question : document.progress().questions()) {
      if (question.resolution() == QuestionResolution.OPEN) {
        ids.add(question.id());
      }
    }
    Collections.sort(ids);
    return ids;
  }

  private static String fingerprintLogical(ChainWorkDocument document) {
    Parts parts = new Parts().add("logical").add(documentId(document));
    List<WorkSource> sources = new ArrayList<>(document.sources());
    sources.sort((left, right) -> left.id().compareTo(right.id()));
    for (WorkSource source : sources) {
      parts.add("source").add(source.id()).add(source.contentHash());
      List<SourcePassage> passages = new ArrayList<>(source.passages());
      passages.sort((left, right) -> left.id().compareTo(right.id()));
      for (SourcePassage passage : passages) {
        parts.add("passage").add(passage.id()).add(passage.contentHash());
      }
    }
    return parts.hash();
  }

  private static String fingerprintBinding(LogicalStep step) {
    Parts parts = new Parts().add("binding").add(step.id());
    appendBinding(parts, step.binding());
    return parts.hash();
  }

  private static String fingerprintOutline(
      ChainWorkDocument document, Graph graph, LogicalStep step, List<String> producers) {
    Parts parts = new Parts().add("outline").add(step.id());
    List<String> requirements = new ArrayList<>(step.requirementIds());
    Collections.sort(requirements);
    for (String requirementId : requirements) {
      parts.add("requirement").add(requirementId);
    }
    appendBinding(parts, step.binding());
    for (String producer : producers) {
      parts.add("producer").add(producer);
      LogicalStep owner = graph.step(producer);
      appendBinding(parts, owner == null ? null : owner.binding());
    }
    List<CoverageEntry> coverage = new ArrayList<>(step.data().outline().coverage());
    coverage.sort(
        (left, right) -> {
          int byRequirement = left.requirementId().compareTo(right.requirementId());
          if (byRequirement != 0) {
            return byRequirement;
          }
          int byPassage = left.passageId().compareTo(right.passageId());
          if (byPassage != 0) {
            return byPassage;
          }
          return left.disposition().name().compareTo(right.disposition().name());
        });
    for (CoverageEntry entry : coverage) {
      parts.add("coverage").add(entry.requirementId()).add(entry.passageId()).add(entry.disposition().name());
    }
    List<DataTransfer> transfers = new ArrayList<>(step.data().transfers());
    transfers.sort((left, right) -> left.id().compareTo(right.id()));
    for (DataTransfer transfer : transfers) {
      appendTransferIdentity(parts, transfer);
    }
    return parts.hash();
  }

  private static String fingerprintContext(Graph graph, String producerId, List<RetainedValue> values) {
    Parts parts = new Parts().add("context").add(producerId);
    LogicalStep producer = graph.step(producerId);
    appendBinding(parts, producer == null ? null : producer.binding());
    List<RetainedValue> sorted = new ArrayList<>(values);
    sorted.sort((left, right) -> left.id().compareTo(right.id()));
    for (RetainedValue value : sorted) {
      parts.add("value").add(value.id()).add(value.resolution().name()).add(fieldPath(value)).add(value.intendedUse());
    }
    return parts.hash();
  }

  private static String fingerprintMapping(ChainWorkDocument document, Graph graph, OwnedTransfer owned) {
    DataTransfer transfer = owned.transfer();
    Parts parts = new Parts().add("transfer").add(transfer.id());
    appendTransferIdentity(parts, transfer);
    List<String> endpoints = new ArrayList<>();
    endpoints.add(owned.stepId());
    for (PortRef source : transfer.sourcePorts()) {
      endpoints.add(source.stepId());
    }
    if (transfer.targetPort() != null) {
      endpoints.add(transfer.targetPort().stepId());
    }
    for (String retainedId : transfer.requiredRetainedIds()) {
      RetainedValue value = graph.retained(retainedId);
      if (value != null) {
        endpoints.add(producerId(value, graph));
        parts.add("retained").add(value.id()).add(value.resolution().name()).add(fieldPath(value));
      } else {
        parts.add("retained").add(retainedId).add("MISSING").add("");
      }
    }
    List<String> uniqueEndpoints = new ArrayList<>(new LinkedHashSet<>(endpoints));
    Collections.sort(uniqueEndpoints);
    for (String stepId : uniqueEndpoints) {
      LogicalStep step = graph.step(stepId);
      parts.add("step").add(stepId);
      appendBinding(parts, step == null ? null : step.binding());
    }
    List<MappingRule> rules = new ArrayList<>(transfer.rules());
    rules.sort((left, right) -> left.id().compareTo(right.id()));
    for (MappingRule rule : rules) {
      parts.add("rule").add(rule.id()).add(rule.target() == null ? "" : rule.target().fieldPath()).add(rule.behavior());
      List<String> evidence = new ArrayList<>(rule.evidenceIds());
      Collections.sort(evidence);
      for (String evidenceId : evidence) {
        parts.add("evidence").add(evidenceId);
      }
    }
    appendGoverningSources(parts, document, transfer.requirementIds());
    return parts.hash();
  }

  private static String fingerprintRepair(OwnedTransfer owned, String ruleId, List<WorkFinding> findings) {
    MappingRule rule = null;
    for (MappingRule candidate : owned.transfer().rules()) {
      if (ruleId.equals(candidate.id())) {
        rule = candidate;
      }
    }
    Parts parts = new Parts().add("repair").add(ruleId).add(owned.transfer().id());
    parts.add(rule == null ? "" : rule.behavior());
    parts.add(rule == null || rule.target() == null ? "" : rule.target().fieldPath());
    List<WorkFinding> sorted = new ArrayList<>(findings);
    sorted.sort((left, right) -> left.id().compareTo(right.id()));
    for (WorkFinding finding : sorted) {
      parts.add("finding").add(finding.id()).add(finding.issueCategory()).add(finding.canonicalFieldPointer());
    }
    return parts.hash();
  }

  private static void appendTransferIdentity(Parts parts, DataTransfer transfer) {
    parts.add("outcome").add(transfer.outcome().name()).add("decision").add(transfer.decision().name());
    List<PortRef> sources = new ArrayList<>(transfer.sourcePorts());
    sources.sort(
        (left, right) -> {
          int byStep = left.stepId().compareTo(right.stepId());
          return byStep != 0 ? byStep : left.portName().compareTo(right.portName());
        });
    for (PortRef source : sources) {
      parts.add("source").add(source.stepId()).add(source.portName());
    }
    PortRef target = transfer.targetPort();
    parts.add("target").add(target == null ? "" : target.stepId()).add(target == null ? "" : target.portName());
    List<String> requirements = new ArrayList<>(transfer.requirementIds());
    Collections.sort(requirements);
    for (String requirementId : requirements) {
      parts.add("requirement").add(requirementId);
    }
    List<String> retained = new ArrayList<>(transfer.requiredRetainedIds());
    Collections.sort(retained);
    for (String retainedId : retained) {
      parts.add("needs").add(retainedId);
    }
  }

  private static void appendBinding(Parts parts, ResolvedWorkBinding binding) {
    if (binding == null) {
      parts.add("unbound");
      return;
    }
    parts.add(binding.operationId())
        .add(binding.version())
        .add(binding.catalogId())
        .add(binding.protocol())
        .add(binding.method())
        .add(binding.path());
    List<String> refs = new ArrayList<>(binding.contractReferences());
    Collections.sort(refs);
    for (String ref : refs) {
      parts.add("ref").add(ref);
    }
    List<String> ports = new ArrayList<>(binding.exposedPorts());
    Collections.sort(ports);
    for (String port : ports) {
      parts.add("port").add(port);
    }
    List<String> schemas = new ArrayList<>();
    for (ResolvedWorkBinding.PortContentHash hash : binding.portContentHashes()) {
      schemas.add(hash.port() + "=" + hash.contentHash());
    }
    Collections.sort(schemas);
    for (String schema : schemas) {
      parts.add("schema").add(schema);
    }
  }

  private static void appendGoverningSources(Parts parts, ChainWorkDocument document, List<String> requirementIds) {
    List<WorkRequirement> requirements = new ArrayList<>();
    Set<String> sourceIds = new LinkedHashSet<>();
    for (WorkRequirement requirement : document.requirements()) {
      if (requirementIds.contains(requirement.id())) {
        requirements.add(requirement);
        sourceIds.addAll(requirement.sourceIds());
      }
    }
    requirements.sort((left, right) -> left.id().compareTo(right.id()));
    for (WorkRequirement requirement : requirements) {
      parts.add("constraint").add(requirement.id()).add(requirement.text());
    }
    List<WorkSource> sources = new ArrayList<>();
    for (WorkSource source : document.sources()) {
      if (sourceIds.contains(source.id())) {
        sources.add(source);
      }
    }
    sources.sort((left, right) -> left.id().compareTo(right.id()));
    for (WorkSource source : sources) {
      parts.add("governing").add(source.id()).add(source.contentHash());
      List<SourcePassage> passages = new ArrayList<>(source.passages());
      passages.sort((left, right) -> left.id().compareTo(right.id()));
      for (SourcePassage passage : passages) {
        parts.add("passage").add(passage.id()).add(passage.contentHash());
      }
    }
  }

  private static List<WorkRequirement> relevant(ChainWorkDocument document, LogicalStep step) {
    Set<String> replaced = new LinkedHashSet<>();
    for (WorkRequirement requirement : document.requirements()) {
      if (!requirement.supersededRequirementId().isBlank()) {
        replaced.add(requirement.supersededRequirementId());
      }
    }
    List<WorkRequirement> relevant = new ArrayList<>();
    for (WorkRequirement requirement : document.requirements()) {
      if (replaced.contains(requirement.id())) {
        continue;
      }
      boolean listed = step.requirementIds().contains(requirement.id());
      boolean replacesListed =
          !requirement.supersededRequirementId().isBlank()
              && step.requirementIds().contains(requirement.supersededRequirementId());
      if (listed || replacesListed) {
        relevant.add(requirement);
      }
    }
    return relevant;
  }

  private static List<CoverageEntry> coverageFor(LogicalStep step, String requirementId) {
    List<CoverageEntry> found = new ArrayList<>();
    for (CoverageEntry entry : step.data().outline().coverage()) {
      if (requirementId.equals(entry.requirementId())) {
        found.add(entry);
      }
    }
    return found;
  }

  private static boolean listedOnTransfer(LogicalStep step, String requirementId) {
    for (DataTransfer transfer : step.data().transfers()) {
      if (transfer.requirementIds().contains(requirementId)) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, List<RetainedValue>> retainedByProducer(ChainWorkDocument document) {
    Map<String, List<RetainedValue>> grouped = new LinkedHashMap<>();
    List<LogicalStep> steps = sortedSteps(document);
    for (LogicalStep step : steps) {
      List<RetainedValue> values = new ArrayList<>(step.data().retainedValues());
      values.sort((left, right) -> left.id().compareTo(right.id()));
      for (RetainedValue value : values) {
        if (value.satisfiesConsumer()) {
          continue;
        }
        String producer = value.producerStepId().isBlank() ? step.id() : value.producerStepId();
        grouped.computeIfAbsent(producer, key -> new ArrayList<>()).add(value);
      }
    }
    return grouped;
  }

  private static List<String> targetsReferencing(ChainWorkDocument document, List<String> retainedIds) {
    Set<String> targets = new LinkedHashSet<>();
    for (OwnedTransfer owned : transfers(document)) {
      for (String retainedId : owned.transfer().requiredRetainedIds()) {
        if (retainedIds.contains(retainedId)) {
          targets.add(owned.stepId());
        }
      }
    }
    List<String> sorted = new ArrayList<>(targets);
    Collections.sort(sorted);
    return sorted;
  }

  private static List<OwnedTransfer> transfers(ChainWorkDocument document) {
    List<OwnedTransfer> owned = new ArrayList<>();
    for (LogicalStep step : sortedSteps(document)) {
      for (DataTransfer transfer : step.data().transfers()) {
        owned.add(new OwnedTransfer(step.id(), transfer));
      }
    }
    owned.sort((left, right) -> left.transfer().id().compareTo(right.transfer().id()));
    return owned;
  }

  private static Map<String, List<WorkFinding>> findingsByRule(ChainWorkDocument document) {
    Map<String, List<WorkFinding>> grouped = new LinkedHashMap<>();
    List<WorkFinding> findings = new ArrayList<>(document.progress().findings());
    findings.sort((left, right) -> left.id().compareTo(right.id()));
    for (WorkFinding finding : findings) {
      if (rule(document, finding.recordRef()) != null) {
        grouped.computeIfAbsent(finding.recordRef(), key -> new ArrayList<>()).add(finding);
      }
    }
    return grouped;
  }

  private static MappingRule rule(ChainWorkDocument document, String ruleId) {
    for (OwnedTransfer owned : transfers(document)) {
      for (MappingRule rule : owned.transfer().rules()) {
        if (ruleId.equals(rule.id())) {
          return rule;
        }
      }
    }
    return null;
  }

  private static OwnedTransfer transferOfRule(ChainWorkDocument document, String ruleId) {
    for (OwnedTransfer owned : transfers(document)) {
      for (MappingRule rule : owned.transfer().rules()) {
        if (ruleId.equals(rule.id())) {
          return owned;
        }
      }
    }
    return null;
  }

  private static String producerId(RetainedValue value, Graph graph) {
    if (!value.producerStepId().isBlank()) {
      return value.producerStepId();
    }
    for (LogicalStep step : graph.steps()) {
      for (RetainedValue stored : step.data().retainedValues()) {
        if (stored.id().equals(value.id())) {
          return step.id();
        }
      }
    }
    return "";
  }

  private static String fieldPath(RetainedValue value) {
    if (value.source() == null) {
      return "";
    }
    return value.source().fieldPath();
  }

  private static List<LogicalStep> sortedSteps(ChainWorkDocument document) {
    List<LogicalStep> steps = new ArrayList<>(document.flow().steps());
    steps.sort((left, right) -> left.id().compareTo(right.id()));
    return steps;
  }

  private static String documentId(ChainWorkDocument document) {
    return document.documentId() == null ? "" : document.documentId();
  }

  private static Draft draft(
      WorkTaskKind kind,
      WorkStage stage,
      String skillId,
      String recordId,
      List<String> assigned,
      List<String> scope,
      List<String> dependencies,
      String fingerprint) {
    return new Draft(
        taskKey(kind, recordId),
        taskId(kind, recordId),
        kind,
        stage,
        skillId,
        recordId,
        sortedUnique(assigned),
        sortedUnique(scope),
        sortedUnique(dependencies),
        fingerprint);
  }

  private static List<String> sortedUnique(List<String> values) {
    List<String> sorted = new ArrayList<>(new LinkedHashSet<>(values));
    Collections.sort(sorted);
    return sorted;
  }

  private static void put(Map<String, Block> blocks, Block block) {
    Block existing = blocks.get(block.taskKey());
    if (existing == null || severity(block.reason()) < severity(existing.reason())) {
      blocks.put(block.taskKey(), block);
    }
  }

  private static int severity(Reason reason) {
    return switch (reason) {
      case DUPLICATE_KEY -> 0;
      case CYCLE -> 1;
      case INVALID_REFERENCE -> 2;
      case MISSING_INPUT -> 3;
      case UNAVAILABLE_INPUT -> 4;
      case UNRESOLVED_INPUT -> 5;
      case COVERAGE_GAP -> 6;
      case UNEVIDENCED_MAPPING -> 7;
      case ACTIVE_FINDING -> 8;
      case WAITING_FOR_INPUT -> 9;
      case WAITING_FOR_TASK -> 10;
    };
  }

  private static boolean structural(Reason reason) {
    return reason != Reason.WAITING_FOR_INPUT && reason != Reason.WAITING_FOR_TASK;
  }

  private static boolean intersects(List<String> left, Iterable<String> right) {
    Set<String> values = new LinkedHashSet<>(left);
    for (String value : right) {
      if (values.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean contains(List<String> values, String candidate) {
    return candidate != null && values.contains(candidate);
  }

  private static String cycleEvidence(Set<String> steps) {
    List<String> sorted = new ArrayList<>(steps);
    Collections.sort(sorted);
    String names;
    if (sorted.size() == 1) {
      names = "Step " + sorted.get(0) + " forms";
    } else if (sorted.size() == 2) {
      names = "Steps " + sorted.get(0) + " and " + sorted.get(1) + " form";
    } else {
      String last = sorted.get(sorted.size() - 1);
      names = "Steps " + String.join(", ", sorted.subList(0, sorted.size() - 1)) + ", and " + last + " form";
    }
    return names + " a design cycle. This cycle is not a loop, retry, or callback.";
  }

  private static int byKindThenKey(Draft left, Draft right) {
    int byKind = Integer.compare(kindRank(left.kind), kindRank(right.kind));
    if (byKind != 0) {
      return byKind;
    }
    return left.taskKey.compareTo(right.taskKey);
  }

  private static int kindRank(WorkTaskKind kind) {
    int index = KIND_ORDER.indexOf(kind);
    return index < 0 ? KIND_ORDER.size() : index;
  }

  private static int byBlock(Block left, Block right) {
    int byKey = left.taskKey().compareTo(right.taskKey());
    if (byKey != 0) {
      return byKey;
    }
    return left.reason().name().compareTo(right.reason().name());
  }

  private static String slug(WorkTaskKind kind) {
    return switch (kind) {
      case LOGICAL_DESIGN -> "logical-design";
      case SELECT_OPERATION -> "select-operation";
      case DEFINE_TRANSFERS -> "define-transfers";
      case DESCRIBE_CONTEXT -> "describe-context";
      case MAP_TRANSFER -> "map-transfer";
      case REPAIR_RULE -> "repair-rule";
      case UNSPECIFIED -> "unspecified";
    };
  }

  private static String sha256(String content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static final class Parts {
    private final StringBuilder payload = new StringBuilder();

    Parts add(String value) {
      if (payload.length() > 0) {
        payload.append('|');
      }
      payload.append(value == null ? "" : value);
      return this;
    }

    String hash() {
      return sha256(payload.toString());
    }
  }

  private static final class Draft {
    private final String taskKey;
    private final String taskId;
    private final WorkTaskKind kind;
    private final WorkStage stage;
    private final String skillId;
    private final String recordId;
    private final List<String> assignedRecordIds;
    private final List<String> scopeIds;
    private final List<String> dependencyKeys;
    private final String fingerprint;
    private WorkTaskState state = WorkTaskState.PENDING;
    private boolean ready;

    private Draft(
        String taskKey,
        String taskId,
        WorkTaskKind kind,
        WorkStage stage,
        String skillId,
        String recordId,
        List<String> assignedRecordIds,
        List<String> scopeIds,
        List<String> dependencyKeys,
        String fingerprint) {
      this.taskKey = taskKey;
      this.taskId = taskId;
      this.kind = kind;
      this.stage = stage;
      this.skillId = skillId;
      this.recordId = recordId;
      this.assignedRecordIds = assignedRecordIds;
      this.scopeIds = scopeIds;
      this.dependencyKeys = dependencyKeys;
      this.fingerprint = fingerprint;
    }

    private Task freeze() {
      return new Task(
          taskKey,
          taskId,
          kind,
          stage,
          skillId,
          recordId,
          assignedRecordIds,
          dependencyKeys,
          fingerprint,
          state,
          ready);
    }
  }

  private record OwnedTransfer(String stepId, DataTransfer transfer) {}

  /**
   * Scheduling edges omit loop and retry back-edges and correlation callbacks.
   * Availability keeps correlation edges and still omits those back-edges.
   */
  private static final class Graph {
    private final Map<String, LogicalStep> steps;
    private final Map<String, RetainedValue> retained;
    private final Map<String, List<String>> reachable;
    private final Map<String, List<String>> producers;
    private final List<Set<String>> branches;
    private final List<Set<String>> bodies;
    private final Set<String> cycleSteps;

    private Graph(
        Map<String, LogicalStep> steps,
        Map<String, RetainedValue> retained,
        Map<String, List<String>> reachable,
        Map<String, List<String>> producers,
        List<Set<String>> branches,
        List<Set<String>> bodies,
        Set<String> cycleSteps) {
      this.steps = steps;
      this.retained = retained;
      this.reachable = reachable;
      this.producers = producers;
      this.branches = branches;
      this.bodies = bodies;
      this.cycleSteps = cycleSteps;
    }

    private static Graph of(ChainWorkDocument document) {
      Map<String, LogicalStep> steps = new LinkedHashMap<>();
      for (LogicalStep step : document.flow().steps()) {
        steps.put(step.id(), step);
      }
      Map<String, RetainedValue> retained = new LinkedHashMap<>();
      for (LogicalStep step : document.flow().steps()) {
        for (RetainedValue value : step.data().retainedValues()) {
          retained.put(value.id(), value);
        }
      }
      Set<String> returns = returnEdges(document);
      Set<String> correlations = correlationEdges(document);
      Map<String, List<String>> forward = new LinkedHashMap<>();
      Map<String, List<String>> reachable = new LinkedHashMap<>();
      Map<String, List<String>> backward = new LinkedHashMap<>();
      for (String stepId : steps.keySet()) {
        forward.put(stepId, new ArrayList<>());
        reachable.put(stepId, new ArrayList<>());
        backward.put(stepId, new ArrayList<>());
      }
      for (LogicalConnection connection : document.flow().connections()) {
        String key = edge(connection);
        boolean returned = returns.contains(key);
        if (!returned) {
          reachable.computeIfAbsent(connection.sourceStepId(), id -> new ArrayList<>()).add(connection.targetStepId());
        }
        if (returned || correlations.contains(key)) {
          continue;
        }
        forward.computeIfAbsent(connection.sourceStepId(), id -> new ArrayList<>()).add(connection.targetStepId());
        backward.computeIfAbsent(connection.targetStepId(), id -> new ArrayList<>()).add(connection.sourceStepId());
      }
      for (List<String> next : forward.values()) {
        Collections.sort(next);
      }
      for (List<String> next : reachable.values()) {
        Collections.sort(next);
      }
      Map<String, List<String>> producers = new LinkedHashMap<>();
      for (String stepId : steps.keySet()) {
        producers.put(stepId, predecessors(stepId, backward));
      }
      List<Set<String>> branches = branches(document, forward);
      List<Set<String>> bodies = bodies(document, forward);
      return new Graph(steps, retained, reachable, producers, branches, bodies, cyclic(forward));
    }

    private LogicalStep step(String stepId) {
      return steps.get(stepId);
    }

    private List<LogicalStep> steps() {
      return List.copyOf(steps.values());
    }

    private RetainedValue retained(String retainedId) {
      return retained.get(retainedId);
    }

    private List<String> producers(String stepId) {
      return producers.getOrDefault(stepId, List.of());
    }

    private boolean available(String producerId, String consumerId) {
      if (producerId == null || producerId.isBlank() || consumerId == null || consumerId.isBlank()) {
        return false;
      }
      if (!producerId.equals(consumerId) && !reaches(producerId, consumerId)) {
        return false;
      }
      for (Set<String> branch : branches) {
        if (branch.contains(producerId) && !branch.contains(consumerId)) {
          return false;
        }
      }
      for (Set<String> body : bodies) {
        if (body.contains(producerId) && !body.contains(consumerId)) {
          return false;
        }
      }
      return true;
    }

    private boolean reaches(String start, String goal) {
      Set<String> seen = new LinkedHashSet<>();
      ArrayDeque<String> pending = new ArrayDeque<>();
      pending.add(start);
      while (!pending.isEmpty()) {
        String current = pending.removeFirst();
        if (!seen.add(current)) {
          continue;
        }
        for (String next : reachable.getOrDefault(current, List.of())) {
          if (goal.equals(next) || goal.equals(current)) {
            return true;
          }
          pending.add(next);
        }
      }
      return seen.contains(goal);
    }

    private static List<String> predecessors(String targetId, Map<String, List<String>> backward) {
      Set<String> reached = new LinkedHashSet<>();
      ArrayDeque<String> pending = new ArrayDeque<>();
      pending.add(targetId);
      while (!pending.isEmpty()) {
        String current = pending.removeFirst();
        for (String source : backward.getOrDefault(current, List.of())) {
          if (reached.add(source)) {
            pending.add(source);
          }
        }
      }
      reached.remove(targetId);
      List<String> sorted = new ArrayList<>(reached);
      Collections.sort(sorted);
      return sorted;
    }

    private static Set<String> correlationEdges(ChainWorkDocument document) {
      Set<String> edges = new LinkedHashSet<>();
      for (LogicalConnection connection : document.flow().connections()) {
        if ("correlation".equals(connection.outcome())) {
          edges.add(edge(connection));
        }
      }
      return edges;
    }

    private static Set<String> returnEdges(ChainWorkDocument document) {
      Set<String> edges = new LinkedHashSet<>();
      for (LogicalConnection connection : document.flow().connections()) {
        for (LoopGroup loop : document.flow().loopGroups()) {
          if (returns(connection, loop.ownerStepId(), loop.bodyEntryStepId(), loop.bodyExitStepIds())) {
            edges.add(edge(connection));
          }
        }
        for (RetryGroup retry : document.flow().retryGroups()) {
          if (returns(connection, retry.ownerStepId(), retry.bodyEntryStepId(), retry.bodyExitStepIds())) {
            edges.add(edge(connection));
          }
        }
      }
      return edges;
    }

    private static boolean returns(LogicalConnection connection, String owner, String entry, List<String> exits) {
      boolean sourceInBody = entry.equals(connection.sourceStepId()) || exits.contains(connection.sourceStepId());
      boolean targetIsReturn = entry.equals(connection.targetStepId()) || owner.equals(connection.targetStepId());
      return sourceInBody && targetIsReturn;
    }

    private static List<Set<String>> branches(ChainWorkDocument document, Map<String, List<String>> forward) {
      List<Set<String>> branches = new ArrayList<>();
      for (ConditionGroup group : document.flow().conditionGroups()) {
        for (ConditionBranch branch : group.branches()) {
          branches.add(region(branch.entryStepId(), group.reconvergenceStepId(), branch.exitStepIds(), forward));
        }
      }
      for (SplitGroup group : document.flow().splitGroups()) {
        for (SplitBranch branch : group.branches()) {
          branches.add(region(branch.entryStepId(), group.reconvergenceStepId(), branch.exitStepIds(), forward));
        }
      }
      for (ErrorScopeGroup scope : document.flow().errorScopeGroups()) {
        for (ErrorHandler handler : scope.handlers()) {
          if (scope.exitStepIds().isEmpty()) {
            branches.add(region(handler.entryStepId(), "", handler.exitStepIds(), forward));
          } else {
            for (String exit : scope.exitStepIds()) {
              branches.add(region(handler.entryStepId(), exit, handler.exitStepIds(), forward));
            }
          }
        }
      }
      return branches;
    }

    private static List<Set<String>> bodies(ChainWorkDocument document, Map<String, List<String>> forward) {
      List<Set<String>> bodies = new ArrayList<>();
      for (LoopGroup loop : document.flow().loopGroups()) {
        bodies.add(region(loop.bodyEntryStepId(), loop.exitStepId(), loop.bodyExitStepIds(), forward));
      }
      for (RetryGroup retry : document.flow().retryGroups()) {
        bodies.add(region(retry.bodyEntryStepId(), retry.exhaustedStepId(), retry.bodyExitStepIds(), forward));
      }
      return bodies;
    }

    private static Set<String> region(String entry, String stopId, List<String> seeds, Map<String, List<String>> forward) {
      Set<String> found = new LinkedHashSet<>();
      ArrayDeque<String> pending = new ArrayDeque<>();
      if (entry != null && !entry.isBlank()) {
        pending.add(entry);
      }
      for (String seed : seeds) {
        if (seed != null && !seed.isBlank()) {
          pending.add(seed);
        }
      }
      while (!pending.isEmpty()) {
        String current = pending.removeFirst();
        if ((stopId != null && stopId.equals(current)) || !found.add(current)) {
          continue;
        }
        for (String next : forward.getOrDefault(current, List.of())) {
          if (stopId == null || !stopId.equals(next)) {
            pending.add(next);
          }
        }
      }
      if (stopId != null) {
        found.remove(stopId);
      }
      return found;
    }

    private static Set<String> cyclic(Map<String, List<String>> forward) {
      Set<String> inCycle = new LinkedHashSet<>();
      Set<String> visited = new LinkedHashSet<>();
      Set<String> stack = new LinkedHashSet<>();
      List<String> nodes = new ArrayList<>(forward.keySet());
      Collections.sort(nodes);
      for (String node : nodes) {
        visit(node, forward, visited, stack, new ArrayList<>(), inCycle);
      }
      return inCycle;
    }

    private static void visit(
        String node,
        Map<String, List<String>> forward,
        Set<String> visited,
        Set<String> stack,
        List<String> path,
        Set<String> inCycle) {
      if (visited.contains(node)) {
        return;
      }
      if (!stack.add(node)) {
        int start = path.indexOf(node);
        if (start >= 0) {
          for (int index = start; index < path.size(); index++) {
            inCycle.add(path.get(index));
          }
        }
        return;
      }
      path.add(node);
      for (String next : forward.getOrDefault(node, List.of())) {
        visit(next, forward, visited, stack, path, inCycle);
      }
      path.remove(path.size() - 1);
      stack.remove(node);
      visited.add(node);
    }

    private static String edge(LogicalConnection connection) {
      return connection.sourceStepId() + "\n" + connection.outcome() + "\n" + connection.targetStepId();
    }
  }

  public record Plan(List<Task> tasks, Task selected, List<Block> blocked, Readiness readiness) {
    public Plan {
      tasks = List.copyOf(tasks);
      blocked = List.copyOf(blocked);
    }
  }

  public record Task(
      String taskKey,
      String taskId,
      WorkTaskKind kind,
      WorkStage stage,
      String skillId,
      String recordId,
      List<String> assignedRecordIds,
      List<String> dependencyKeys,
      String requiredInputFingerprint,
      WorkTaskState state,
      boolean ready) {
    public Task {
      assignedRecordIds = List.copyOf(assignedRecordIds);
      dependencyKeys = List.copyOf(dependencyKeys);
    }
  }

  public record Block(String taskKey, Reason reason, String evidence) {}

  public enum Reason {
    DUPLICATE_KEY,
    CYCLE,
    INVALID_REFERENCE,
    MISSING_INPUT,
    UNAVAILABLE_INPUT,
    UNRESOLVED_INPUT,
    COVERAGE_GAP,
    UNEVIDENCED_MAPPING,
    ACTIVE_FINDING,
    WAITING_FOR_INPUT,
    WAITING_FOR_TASK
  }

  public record Readiness(Status status, List<String> questionIds, List<Block> defects) {
    public Readiness {
      questionIds = List.copyOf(questionIds);
      defects = List.copyOf(defects);
    }

    public enum Status {
      WORK_REMAINING,
      WAITING_FOR_INPUT,
      HALTED,
      READY_FOR_PRESENTATION
    }
  }
}
