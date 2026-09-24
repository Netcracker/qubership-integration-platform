package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.qubership.integration.platform.ai.plan.mapping.TypedMappingCompiler;

/** Applies complete small records inside a server-owned scope. */
final class WorkDocumentEditor {

  WorkCommit apply(
      WorkDocumentState state, WorkTaskScope scope, WorkTaskCapture capture, String commandId) {
    if (state.document().schemaVersion() != 1) {
      throw reject("MALFORMED_REFERENCE", "Document schema version must be 1.");
    }
    if (!scope.baseRevision().equals(state.revision())) {
      throw reject(
          "STALE_SCOPE",
          "Scope revision does not match the current document. Read the document and submit the capture again.");
    }
    rejectContradictory(capture);
    if (capture.outcome() != WorkOutcome.PREPARED) {
      return acceptWithoutRecords(state, scope, capture, commandId);
    }
    Map<String, String> aliases = new LinkedHashMap<>();
    Set<String> known = knownIds(state.document());
    assign(aliases, known, capture);
    Draft draft = Draft.from(state.document());
    List<String> accepted = new ArrayList<>();
    applyRequirements(draft, scope, capture, aliases, known, accepted);
    applySteps(draft, scope, capture, aliases, known, accepted);
    applyConnections(draft, scope, capture, aliases, known, accepted);
    applyGroups(draft, scope, capture, aliases, known, accepted);
    applyTransfers(draft, scope, capture, aliases, known, accepted);
    applyRules(draft, scope, capture, aliases, known, accepted);
    applyRetained(draft, scope, capture, aliases, known, accepted);
    applyDeletes(draft, scope, capture, known, accepted);
    WorkProgress progress = withTask(draft.progress, scope, WorkTaskState.ACCEPTED);
    ChainWorkDocument next =
        new ChainWorkDocument(
            draft.schemaVersion,
            draft.documentId,
            draft.sources,
            draft.requirements,
            draft.flow(),
            progress);
    WorkDocumentState committed = WorkDocumentState.of(next);
    return new WorkCommit(
        committed.revision(),
        List.copyOf(accepted),
        WorkOutcome.PREPARED,
        commandId,
        committed,
        Map.copyOf(aliases));
  }

  WorkDocumentState attachResolvedBinding(
      WorkDocumentState state, String stepId, ResolvedWorkBinding binding) {
    List<LogicalStep> steps = new ArrayList<>();
    boolean found = false;
    for (LogicalStep step : state.document().flow().steps()) {
      if (step.id().equals(stepId)) {
        found = true;
        steps.add(
            new LogicalStep(
                step.id(),
                step.kind(),
                step.label(),
                step.intent(),
                step.sourceIds(),
                step.requirementIds(),
                binding,
                step.data()));
      } else {
        steps.add(step);
      }
    }
    if (!found) {
      throw reject("MALFORMED_REFERENCE", "Step " + stepId + " does not exist. Attach a binding to an existing step.");
    }
    LogicalFlow flow = state.document().flow();
    ChainWorkDocument next =
        new ChainWorkDocument(
            state.document().schemaVersion(),
            state.document().documentId(),
            state.document().sources(),
            state.document().requirements(),
            new LogicalFlow(
                steps,
                flow.connections(),
                flow.sequenceGroups(),
                flow.conditionGroups(),
                flow.splitGroups(),
                flow.loopGroups(),
                flow.retryGroups(),
                flow.errorScopeGroups()),
            state.document().progress());
    return WorkDocumentState.of(next);
  }

  private static WorkCommit acceptWithoutRecords(
      WorkDocumentState state, WorkTaskScope scope, WorkTaskCapture capture, String commandId) {
    if (!capture.requirements().isEmpty()
        || !capture.steps().isEmpty()
        || !capture.connections().isEmpty()
        || !capture.rules().isEmpty()
        || !capture.deletes().isEmpty()) {
      throw reject(
          "CONTRADICTORY_OUTCOME",
          "Outcome " + capture.outcome() + " cannot include design records. Send the question or the defect alone.");
    }
    WorkProgress prior = state.document().progress();
    List<WorkQuestion> questions = Lists.mutable(prior.questions());
    List<WorkFinding> findings = Lists.mutable(prior.findings());
    WorkTaskState taskState;
    if (capture.outcome() == WorkOutcome.NEEDS_CLARIFICATION) {
      taskState = WorkTaskState.NEEDS_INPUT;
      questions.add(
          new WorkQuestion(
              newId(),
              capture.unresolvedChoice(),
              capture.question(),
              resolveAll(capture.clarificationEvidenceIds(), Map.of(), sourceIds(state.document()))));
    } else {
      taskState = WorkTaskState.NEEDS_RECHECK;
      findings.add(
          new WorkFinding(
              newId(),
              resolve(capture.defectRecordRef(), Map.of(), knownIds(state.document()), true),
              capture.issueCategory(),
              capture.contradiction(),
              resolveAll(capture.defectEvidenceIds(), Map.of(), sourceIds(state.document()))));
    }
    List<WorkTaskRecord> tasks = Lists.mutable(prior.tasks());
    tasks.add(new WorkTaskRecord(scope.taskId(), taskState, scope.stage(), scope.skillId()));
    WorkProgress progress =
        new WorkProgress(
            tasks,
            findings,
            questions,
            prior.approvalReference(),
            prior.derivedResultReferences(),
            prior.recheckStages());
    ChainWorkDocument next =
        new ChainWorkDocument(
            state.document().schemaVersion(),
            state.document().documentId(),
            state.document().sources(),
            state.document().requirements(),
            state.document().flow(),
            progress);
    WorkDocumentState committed = WorkDocumentState.of(next);
    return new WorkCommit(
        committed.revision(), List.of(), capture.outcome(), commandId, committed, Map.of());
  }

  private static void rejectContradictory(WorkTaskCapture capture) {
    boolean preparedPayload =
        !capture.requirements().isEmpty()
            || !capture.steps().isEmpty()
            || !capture.connections().isEmpty()
            || !capture.sequenceGroups().isEmpty()
            || !capture.conditionGroups().isEmpty()
            || !capture.splitGroups().isEmpty()
            || !capture.loopGroups().isEmpty()
            || !capture.retryGroups().isEmpty()
            || !capture.errorScopeGroups().isEmpty()
            || !capture.transfers().isEmpty()
            || !capture.rules().isEmpty()
            || !capture.retainedValues().isEmpty()
            || !capture.deletes().isEmpty();
    boolean clarification = !capture.question().isBlank() || !capture.unresolvedChoice().isBlank();
    boolean defect = !capture.defectRecordRef().isBlank() || !capture.contradiction().isBlank();
    if (capture.outcome() == WorkOutcome.PREPARED && (clarification || defect)) {
      throw reject(
          "CONTRADICTORY_OUTCOME",
          "A prepared capture cannot also ask a question or report a defect. Send one outcome.");
    }
    if (capture.outcome() == WorkOutcome.NEEDS_CLARIFICATION && (preparedPayload || defect || capture.question().isBlank())) {
      throw reject(
          "CONTRADICTORY_OUTCOME",
          "A clarification needs one question and no design records. Remove the other fields.");
    }
    if (capture.outcome() == WorkOutcome.INPUT_DEFECT && (preparedPayload || clarification || capture.defectRecordRef().isBlank())) {
      throw reject(
          "CONTRADICTORY_OUTCOME",
          "An input defect needs the existing record reference and no design records. Remove the other fields.");
    }
  }

  private void applyRequirements(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Map<String, String> aliases,
      Set<String> known,
      List<String> accepted) {
    for (CapturedRequirement requirement : capture.requirements()) {
      String id = permit(scope, requirement.existingId(), requirement.alias(), aliases);
      for (String source : requirement.sourceRefs()) {
        resolve(source, aliases, sourceIds(draft), true);
      }
      String superseded = resolve(requirement.supersededRef(), aliases, known, false);
      WorkRequirement stored =
          new WorkRequirement(id, requirement.text(), resolveAll(requirement.sourceRefs(), aliases, sourceIds(draft)), superseded);
      upsert(draft.requirements, stored.id(), stored, WorkRequirement::id);
      known.add(id);
      accepted.add(id);
    }
  }

  private void applySteps(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Map<String, String> aliases,
      Set<String> known,
      List<String> accepted) {
    for (CapturedStep captured : capture.steps()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      List<String> sources = resolveAll(captured.sourceRefs(), aliases, sourceIds(draft));
      List<String> requirements = resolveAll(captured.requirementRefs(), aliases, known);
      LogicalStep previous = find(draft.steps, id, LogicalStep::id);
      LogicalStep stored =
          new LogicalStep(
              id,
              captured.kind(),
              captured.label(),
              captured.intent(),
              sources,
              requirements,
              previous == null ? null : previous.binding(),
              previous == null ? StepData.empty() : previous.data());
      upsert(draft.steps, id, stored, LogicalStep::id);
      known.add(id);
      accepted.add(id);
    }
  }

  private void applyConnections(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Map<String, String> aliases,
      Set<String> known,
      List<String> accepted) {
    Set<String> steps = ids(draft.steps, LogicalStep::id);
    for (CapturedConnection captured : capture.connections()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      LogicalConnection stored =
          new LogicalConnection(
              id,
              resolve(captured.sourceStepRef(), aliases, steps, true),
              captured.outcome(),
              resolve(captured.targetStepRef(), aliases, steps, true),
              captured.routingIntent(),
              resolveAll(captured.evidenceRefs(), aliases, sourceIds(draft)));
      upsert(draft.connections, id, stored, LogicalConnection::id);
      known.add(id);
      accepted.add(id);
    }
  }

  private void applyGroups(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Map<String, String> aliases,
      Set<String> known,
      List<String> accepted) {
    Set<String> steps = ids(draft.steps, LogicalStep::id);
    for (CapturedSequenceGroup captured : capture.sequenceGroups()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      upsert(
          draft.sequenceGroups,
          id,
          new SequenceGroup(id, resolveAll(captured.memberStepRefs(), aliases, steps)),
          SequenceGroup::id);
      known.add(id);
      accepted.add(id);
    }
    for (CapturedConditionGroup captured : capture.conditionGroups()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      List<ConditionBranch> branches = new ArrayList<>();
      for (CapturedConditionBranch branch : captured.branches()) {
        String branchId = permit(scope, branch.existingId(), branch.alias(), aliases);
        branches.add(
            new ConditionBranch(
                branchId,
                branch.role(),
                branch.predicate(),
                branch.priority(),
                resolve(branch.entryStepRef(), aliases, steps, true),
                resolveAll(branch.exitStepRefs(), aliases, steps)));
        known.add(branchId);
        accepted.add(branchId);
      }
      upsert(
          draft.conditionGroups,
          id,
          new ConditionGroup(
              id,
              resolve(captured.ownerStepRef(), aliases, steps, true),
              branches,
              resolve(captured.reconvergenceStepRef(), aliases, steps, false)),
          ConditionGroup::id);
      known.add(id);
      accepted.add(id);
    }
    for (CapturedSplitGroup captured : capture.splitGroups()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      List<SplitBranch> branches = new ArrayList<>();
      for (CapturedSplitBranch branch : captured.branches()) {
        String branchId = permit(scope, branch.existingId(), branch.alias(), aliases);
        branches.add(
            new SplitBranch(
                branchId,
                branch.order(),
                resolve(branch.entryStepRef(), aliases, steps, true),
                resolveAll(branch.exitStepRefs(), aliases, steps)));
        known.add(branchId);
        accepted.add(branchId);
      }
      upsert(
          draft.splitGroups,
          id,
          new SplitGroup(
              id,
              resolve(captured.ownerStepRef(), aliases, steps, true),
              captured.mode(),
              branches,
              resolve(captured.reconvergenceStepRef(), aliases, steps, false)),
          SplitGroup::id);
      known.add(id);
      accepted.add(id);
    }
    for (CapturedLoopGroup captured : capture.loopGroups()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      upsert(
          draft.loopGroups,
          id,
          new LoopGroup(
              id,
              resolve(captured.ownerStepRef(), aliases, steps, true),
              resolve(captured.bodyEntryStepRef(), aliases, steps, true),
              resolveAll(captured.bodyExitStepRefs(), aliases, steps),
              resolve(captured.exitStepRef(), aliases, steps, true),
              captured.loopMode(),
              captured.loopExpression(),
              captured.loopSafetyBound()),
          LoopGroup::id);
      known.add(id);
      accepted.add(id);
    }
    for (CapturedRetryGroup captured : capture.retryGroups()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      upsert(
          draft.retryGroups,
          id,
          new RetryGroup(
              id,
              resolve(captured.ownerStepRef(), aliases, steps, true),
              resolve(captured.bodyEntryStepRef(), aliases, steps, true),
              resolveAll(captured.bodyExitStepRefs(), aliases, steps),
              resolve(captured.exhaustedStepRef(), aliases, steps, true),
              captured.retryCount(),
              captured.retryDelayMillis()),
          RetryGroup::id);
      known.add(id);
      accepted.add(id);
    }
    for (CapturedErrorScopeGroup captured : capture.errorScopeGroups()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      List<ErrorHandler> handlers = new ArrayList<>();
      for (CapturedErrorHandler handler : captured.handlers()) {
        String handlerId = permit(scope, handler.existingId(), handler.alias(), aliases);
        handlers.add(
            new ErrorHandler(
                handlerId,
                handler.exceptionClass(),
                resolve(handler.entryStepRef(), aliases, steps, true),
                resolveAll(handler.exitStepRefs(), aliases, steps)));
        known.add(handlerId);
        accepted.add(handlerId);
      }
      upsert(
          draft.errorScopeGroups,
          id,
          new ErrorScopeGroup(
              id,
              resolve(captured.ownerStepRef(), aliases, steps, true),
              resolve(captured.tryEntryStepRef(), aliases, steps, true),
              handlers,
              resolve(captured.finallyEntryStepRef(), aliases, steps, false),
              resolveAll(captured.exitStepRefs(), aliases, steps)),
          ErrorScopeGroup::id);
      known.add(id);
      accepted.add(id);
    }
  }

  private void applyTransfers(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Map<String, String> aliases,
      Set<String> known,
      List<String> accepted) {
    for (CapturedTransfer captured : capture.transfers()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      String targetStep = resolve(captured.targetStepRef(), aliases, ids(draft.steps, LogicalStep::id), true);
      List<PortRef> sources = new ArrayList<>();
      for (PortRef port : captured.sourcePorts()) {
        sources.add(checkedPort(draft, aliases, port));
      }
      PortRef target = checkedPort(draft, aliases, captured.targetPort());
      DataTransfer previous = findTransfer(draft, id);
      DataTransfer stored =
          new DataTransfer(
              id,
              sources,
              target,
              resolveAll(captured.requirementRefs(), aliases, known),
              previous == null ? List.of() : previous.rules(),
              decision(captured.decision()));
      replaceTransfer(draft, targetStep, stored);
      known.add(id);
      accepted.add(id);
    }
  }

  private void applyRules(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Map<String, String> aliases,
      Set<String> known,
      List<String> accepted) {
    for (CapturedRule captured : capture.rules()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      String transferId = resolve(captured.transferRef(), aliases, known, true);
      List<FieldReference> sources = new ArrayList<>();
      for (FieldReference source : captured.sources()) {
        sources.add(checkedField(draft, aliases, known, source));
      }
      MappingRule stored =
          new MappingRule(
              id,
              sources,
              checkedField(draft, aliases, known, captured.target()),
              captured.constants(),
              captured.behavior(),
              resolveAll(captured.evidenceRefs(), aliases, sourceIds(draft)));
      replaceRule(draft, transferId, stored);
      known.add(id);
      accepted.add(id);
    }
  }

  private void applyRetained(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Map<String, String> aliases,
      Set<String> known,
      List<String> accepted) {
    for (CapturedRetainedValue captured : capture.retainedValues()) {
      String id = permit(scope, captured.existingId(), captured.alias(), aliases);
      String stepId = resolve(captured.stepRef(), aliases, ids(draft.steps, LogicalStep::id), true);
      RetainedValue stored =
          new RetainedValue(
              id,
              checkedField(draft, aliases, known, captured.source()),
              captured.intendedUse(),
              resolveAll(captured.evidenceRefs(), aliases, sourceIds(draft)));
      replaceRetained(draft, stepId, stored);
      known.add(id);
      accepted.add(id);
    }
  }

  private static void applyDeletes(
      Draft draft, WorkTaskScope scope, WorkTaskCapture capture, Set<String> known, List<String> accepted) {
    if (capture.deletes().isEmpty()) {
      return;
    }
    if (!scope.deletePermitted()) {
      throw reject(
          "UNAUTHORIZED_DELETE",
          "Delete is not permitted for this task. Request a scope that allows deletion.");
    }
    for (CapturedDelete delete : capture.deletes()) {
      if (delete.existingId() == null || delete.existingId().isBlank() || !known.contains(delete.existingId())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Delete target " + delete.existingId() + " does not exist. Name an existing record.");
      }
      if (!scope.ownedRecordIds().contains(delete.existingId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Record " + delete.existingId() + " is outside the assigned scope. Delete only owned records.");
      }
      if (delete.evidenceRefs() == null || delete.evidenceRefs().isEmpty()) {
        throw reject(
            "UNAUTHORIZED_DELETE",
            "Delete requires source evidence. Name the source that authorizes the deletion.");
      }
      resolveAll(delete.evidenceRefs(), Map.of(), sourceIds(draft));
      remove(draft, delete.existingId());
      if (stillReferenced(draft, delete.existingId())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Deleting " + delete.existingId() + " leaves a dangling reference. Delete or retarget the dependent records in this capture.");
      }
      known.remove(delete.existingId());
      accepted.add(delete.existingId());
    }
  }

  private static String permit(
      WorkTaskScope scope, String existingId, String alias, Map<String, String> aliases) {
    boolean hasExisting = existingId != null && !existingId.isBlank();
    boolean hasAlias = alias != null && !alias.isBlank();
    if (hasExisting == hasAlias) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Provide an alias for a new record or an existing id for a replacement, not both.");
    }
    if (hasAlias) {
      if (!scope.createPermitted()) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Creation is not permitted in this scope. Replace an owned record or request create permission.");
      }
      return aliases.get(alias);
    }
    if (!scope.replacePermitted() || !scope.ownedRecordIds().contains(existingId)) {
      throw reject(
          "OUTSIDE_SCOPE",
          "Record " + existingId + " is outside the assigned scope. Submit only records this task owns.");
    }
    return existingId;
  }

  private static void assign(Map<String, String> aliases, Set<String> known, WorkTaskCapture capture) {
    for (CapturedRequirement record : capture.requirements()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedStep record : capture.steps()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedConnection record : capture.connections()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedSequenceGroup record : capture.sequenceGroups()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedConditionGroup record : capture.conditionGroups()) {
      assignOne(aliases, known, record.existingId(), record.alias());
      for (CapturedConditionBranch branch : record.branches()) {
        assignOne(aliases, known, branch.existingId(), branch.alias());
      }
    }
    for (CapturedSplitGroup record : capture.splitGroups()) {
      assignOne(aliases, known, record.existingId(), record.alias());
      for (CapturedSplitBranch branch : record.branches()) {
        assignOne(aliases, known, branch.existingId(), branch.alias());
      }
    }
    for (CapturedLoopGroup record : capture.loopGroups()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedRetryGroup record : capture.retryGroups()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedErrorScopeGroup record : capture.errorScopeGroups()) {
      assignOne(aliases, known, record.existingId(), record.alias());
      for (CapturedErrorHandler handler : record.handlers()) {
        assignOne(aliases, known, handler.existingId(), handler.alias());
      }
    }
    for (CapturedTransfer record : capture.transfers()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedRule record : capture.rules()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
    for (CapturedRetainedValue record : capture.retainedValues()) {
      assignOne(aliases, known, record.existingId(), record.alias());
    }
  }

  private static void assignOne(Map<String, String> aliases, Set<String> known, String existingId, String alias) {
    if (alias == null || alias.isBlank()) {
      return;
    }
    if (aliases.containsKey(alias)) {
      throw reject("MALFORMED_REFERENCE", "Alias " + alias + " is used twice. Use one alias per new record.");
    }
    String id = "wd-" + UUID.randomUUID();
    aliases.put(alias, id);
    known.add(id);
  }

  private static String resolve(String ref, Map<String, String> aliases, Set<String> known, boolean required) {
    if (ref == null || ref.isBlank()) {
      if (required) {
        throw reject("MALFORMED_REFERENCE", "A required reference is missing. Name an existing id or a creation alias.");
      }
      return "";
    }
    if (aliases.containsKey(ref)) {
      return aliases.get(ref);
    }
    if (known.contains(ref)) {
      return ref;
    }
    throw reject(
        "MALFORMED_REFERENCE",
        "Reference " + ref + " does not identify a record. Use an existing id or a creation alias from this capture.");
  }

  private static List<String> resolveAll(List<String> refs, Map<String, String> aliases, Set<String> known) {
    if (refs == null) {
      return List.of();
    }
    List<String> resolved = new ArrayList<>();
    for (String ref : refs) {
      resolved.add(resolve(ref, aliases, known, true));
    }
    return List.copyOf(resolved);
  }

  private static FieldReference checkedField(
      Draft draft, Map<String, String> aliases, Set<String> known, FieldReference reference) {
    if (reference == null || reference.kind() == null) {
      throw reject("MALFORMED_REFERENCE", "A field reference needs a kind. Use a step port or a retained value.");
    }
    if (reference.kind() == FieldReferenceKind.RETAINED) {
      String retained = resolve(reference.retainedValueId(), aliases, known, true);
      return new FieldReference(FieldReferenceKind.RETAINED, "", null, "", retained);
    }
    String stepId = resolve(reference.stepId(), aliases, ids(draft.steps, LogicalStep::id), true);
    if (!TypedMappingCompiler.validPath(reference.fieldPath())) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Field path " + reference.fieldPath() + " is not a canonical payload path. Use a $.field path.");
    }
    return new FieldReference(FieldReferenceKind.STEP_PORT, stepId, reference.port(), reference.fieldPath(), "");
  }

  private static PortRef checkedPort(Draft draft, Map<String, String> aliases, PortRef port) {
    if (port == null) {
      throw reject("MALFORMED_REFERENCE", "A transfer port is missing. Name the step and the port.");
    }
    String stepId = resolve(port.stepId(), aliases, ids(draft.steps, LogicalStep::id), true);
    LogicalStep step = find(draft.steps, stepId, LogicalStep::id);
    if (step.binding() != null && !step.binding().exposedPorts().contains(port.portName())) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Port " + port.portName() + " is not on the selected contract for step " + stepId + ". Choose an exposed port.");
    }
    return new PortRef(stepId, port.portName());
  }

  private static MappingDecision decision(String value) {
    if (value == null || value.isBlank()) {
      return MappingDecision.UNSPECIFIED;
    }
    if ("NO_MAPPING".equals(value)) {
      return MappingDecision.NO_MAPPING;
    }
    throw reject(
        "MALFORMED_REFERENCE",
        "Mapping decision " + value + " is unknown. Use an empty decision or NO_MAPPING.");
  }

  private static Set<String> knownIds(ChainWorkDocument document) {
    Set<String> known = new LinkedHashSet<>();
    document.requirements().forEach(record -> known.add(record.id()));
    document.flow().steps().forEach(record -> known.add(record.id()));
    document.flow().connections().forEach(record -> known.add(record.id()));
    document.flow().sequenceGroups().forEach(record -> known.add(record.id()));
    document.flow().conditionGroups().forEach(record -> {
      known.add(record.id());
      record.branches().forEach(branch -> known.add(branch.id()));
    });
    document.flow().splitGroups().forEach(record -> {
      known.add(record.id());
      record.branches().forEach(branch -> known.add(branch.id()));
    });
    document.flow().loopGroups().forEach(record -> known.add(record.id()));
    document.flow().retryGroups().forEach(record -> known.add(record.id()));
    document.flow().errorScopeGroups().forEach(record -> {
      known.add(record.id());
      record.handlers().forEach(handler -> known.add(handler.id()));
    });
    for (LogicalStep step : document.flow().steps()) {
      step.data().transfers().forEach(transfer -> {
        known.add(transfer.id());
        transfer.rules().forEach(rule -> known.add(rule.id()));
      });
      step.data().retainedValues().forEach(value -> known.add(value.id()));
    }
    return known;
  }

  private static Set<String> sourceIds(Draft draft) {
    return ids(draft.sources, WorkSource::id);
  }

  private static Set<String> sourceIds(ChainWorkDocument document) {
    return ids(document.sources(), WorkSource::id);
  }

  private static String newId() {
    return "wd-" + UUID.randomUUID();
  }

  private static WorkProgress withTask(WorkProgress progress, WorkTaskScope scope, WorkTaskState state) {
    List<WorkTaskRecord> tasks = Lists.mutable(progress.tasks());
    tasks.add(new WorkTaskRecord(scope.taskId(), state, scope.stage(), scope.skillId()));
    return new WorkProgress(
        tasks,
        progress.findings(),
        progress.questions(),
        progress.approvalReference(),
        progress.derivedResultReferences(),
        progress.recheckStages());
  }

  private static DataTransfer findTransfer(Draft draft, String id) {
    for (LogicalStep step : draft.steps) {
      for (DataTransfer transfer : step.data().transfers()) {
        if (transfer.id().equals(id)) {
          return transfer;
        }
      }
    }
    return null;
  }

  private static void replaceTransfer(Draft draft, String targetStepId, DataTransfer stored) {
    for (int i = 0; i < draft.steps.size(); i++) {
      LogicalStep step = draft.steps.get(i);
      List<DataTransfer> transfers = new ArrayList<>();
      boolean onStep = step.id().equals(targetStepId);
      boolean replaced = false;
      for (DataTransfer transfer : step.data().transfers()) {
        if (transfer.id().equals(stored.id())) {
          if (!onStep) {
            continue;
          }
          transfers.add(stored);
          replaced = true;
        } else {
          transfers.add(transfer);
        }
      }
      if (onStep && !replaced) {
        transfers.add(stored);
      }
      if (onStep || replaced) {
        draft.steps.set(
            i,
            new LogicalStep(
                step.id(),
                step.kind(),
                step.label(),
                step.intent(),
                step.sourceIds(),
                step.requirementIds(),
                step.binding(),
                new StepData(transfers, step.data().retainedValues())));
      }
    }
  }

  private static void replaceRule(Draft draft, String transferId, MappingRule stored) {
    boolean found = false;
    for (int i = 0; i < draft.steps.size(); i++) {
      LogicalStep step = draft.steps.get(i);
      List<DataTransfer> transfers = new ArrayList<>();
      boolean stepChanged = false;
      for (DataTransfer transfer : step.data().transfers()) {
        if (!transfer.id().equals(transferId)) {
          transfers.add(transfer);
          continue;
        }
        found = true;
        stepChanged = true;
        List<MappingRule> rules = new ArrayList<>();
        boolean replaced = false;
        for (MappingRule rule : transfer.rules()) {
          if (rule.id().equals(stored.id())) {
            rules.add(stored);
            replaced = true;
          } else {
            rules.add(rule);
          }
        }
        if (!replaced) {
          rules.add(stored);
        }
        transfers.add(
            new DataTransfer(
                transfer.id(),
                transfer.sourcePorts(),
                transfer.targetPort(),
                transfer.requirementIds(),
                rules,
                transfer.decision()));
      }
      if (stepChanged) {
        draft.steps.set(
            i,
            new LogicalStep(
                step.id(),
                step.kind(),
                step.label(),
                step.intent(),
                step.sourceIds(),
                step.requirementIds(),
                step.binding(),
                new StepData(transfers, step.data().retainedValues())));
      }
    }
    if (!found) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Transfer " + transferId + " does not exist. Add the transfer before its rules.");
    }
  }

  private static void replaceRetained(Draft draft, String stepId, RetainedValue stored) {
    for (int i = 0; i < draft.steps.size(); i++) {
      LogicalStep step = draft.steps.get(i);
      if (!step.id().equals(stepId)) {
        continue;
      }
      List<RetainedValue> values = new ArrayList<>();
      boolean replaced = false;
      for (RetainedValue value : step.data().retainedValues()) {
        if (value.id().equals(stored.id())) {
          values.add(stored);
          replaced = true;
        } else {
          values.add(value);
        }
      }
      if (!replaced) {
        values.add(stored);
      }
      draft.steps.set(
          i,
          new LogicalStep(
              step.id(),
              step.kind(),
              step.label(),
              step.intent(),
              step.sourceIds(),
              step.requirementIds(),
              step.binding(),
              new StepData(step.data().transfers(), values)));
      return;
    }
  }

  private static void remove(Draft draft, String id) {
    draft.requirements.removeIf(record -> record.id().equals(id));
    draft.steps.removeIf(record -> record.id().equals(id));
    draft.connections.removeIf(record -> record.id().equals(id));
    draft.sequenceGroups.removeIf(record -> record.id().equals(id));
    draft.conditionGroups.removeIf(record -> record.id().equals(id));
    draft.splitGroups.removeIf(record -> record.id().equals(id));
    draft.loopGroups.removeIf(record -> record.id().equals(id));
    draft.retryGroups.removeIf(record -> record.id().equals(id));
    draft.errorScopeGroups.removeIf(record -> record.id().equals(id));
    for (int i = 0; i < draft.steps.size(); i++) {
      LogicalStep step = draft.steps.get(i);
      List<DataTransfer> transfers = new ArrayList<>();
      boolean changed = false;
      for (DataTransfer transfer : step.data().transfers()) {
        if (transfer.id().equals(id)) {
          changed = true;
          continue;
        }
        List<MappingRule> rules = new ArrayList<>();
        boolean ruleRemoved = false;
        for (MappingRule rule : transfer.rules()) {
          if (rule.id().equals(id)) {
            ruleRemoved = true;
          } else {
            rules.add(rule);
          }
        }
        if (ruleRemoved) {
          changed = true;
          transfers.add(
              new DataTransfer(
                  transfer.id(),
                  transfer.sourcePorts(),
                  transfer.targetPort(),
                  transfer.requirementIds(),
                  rules,
                  transfer.decision()));
        } else {
          transfers.add(transfer);
        }
      }
      List<RetainedValue> retained = new ArrayList<>();
      for (RetainedValue value : step.data().retainedValues()) {
        if (value.id().equals(id)) {
          changed = true;
        } else {
          retained.add(value);
        }
      }
      if (changed) {
        draft.steps.set(
            i,
            new LogicalStep(
                step.id(),
                step.kind(),
                step.label(),
                step.intent(),
                step.sourceIds(),
                step.requirementIds(),
                step.binding(),
                new StepData(transfers, retained)));
      }
    }
  }

  private static boolean stillReferenced(Draft draft, String id) {
    for (LogicalConnection connection : draft.connections) {
      if (id.equals(connection.sourceStepId()) || id.equals(connection.targetStepId())) {
        return true;
      }
    }
    for (LogicalStep step : draft.steps) {
      if (step.requirementIds().contains(id)) {
        return true;
      }
      for (DataTransfer transfer : step.data().transfers()) {
        if (transfer.requirementIds().contains(id) || id.equals(transfer.id())) {
          return true;
        }
        for (PortRef port : transfer.sourcePorts()) {
          if (id.equals(port.stepId())) {
            return true;
          }
        }
        if (transfer.targetPort() != null && id.equals(transfer.targetPort().stepId())) {
          return true;
        }
      }
    }
    return false;
  }

  private static <T> void upsert(List<T> records, String id, T stored, java.util.function.Function<T, String> idOf) {
    for (int i = 0; i < records.size(); i++) {
      if (id.equals(idOf.apply(records.get(i)))) {
        records.set(i, stored);
        return;
      }
    }
    records.add(stored);
  }

  private static <T> T find(List<T> records, String id, java.util.function.Function<T, String> idOf) {
    for (T record : records) {
      if (id.equals(idOf.apply(record))) {
        return record;
      }
    }
    return null;
  }

  private static <T> Set<String> ids(List<T> records, java.util.function.Function<T, String> idOf) {
    Set<String> ids = new LinkedHashSet<>();
    for (T record : records) {
      ids.add(idOf.apply(record));
    }
    return ids;
  }

  private static WorkDocumentRejectedException reject(String code, String message) {
    return new WorkDocumentRejectedException(code, message);
  }

  private static final class Draft {
    final int schemaVersion;
    final String documentId;
    final List<WorkSource> sources;
    final List<WorkRequirement> requirements;
    final List<LogicalStep> steps;
    final List<LogicalConnection> connections;
    final List<SequenceGroup> sequenceGroups;
    final List<ConditionGroup> conditionGroups;
    final List<SplitGroup> splitGroups;
    final List<LoopGroup> loopGroups;
    final List<RetryGroup> retryGroups;
    final List<ErrorScopeGroup> errorScopeGroups;
    final WorkProgress progress;

    private Draft(
        int schemaVersion,
        String documentId,
        List<WorkSource> sources,
        List<WorkRequirement> requirements,
        List<LogicalStep> steps,
        List<LogicalConnection> connections,
        List<SequenceGroup> sequenceGroups,
        List<ConditionGroup> conditionGroups,
        List<SplitGroup> splitGroups,
        List<LoopGroup> loopGroups,
        List<RetryGroup> retryGroups,
        List<ErrorScopeGroup> errorScopeGroups,
        WorkProgress progress) {
      this.schemaVersion = schemaVersion;
      this.documentId = documentId;
      this.sources = sources;
      this.requirements = requirements;
      this.steps = steps;
      this.connections = connections;
      this.sequenceGroups = sequenceGroups;
      this.conditionGroups = conditionGroups;
      this.splitGroups = splitGroups;
      this.loopGroups = loopGroups;
      this.retryGroups = retryGroups;
      this.errorScopeGroups = errorScopeGroups;
      this.progress = progress;
    }

    static Draft from(ChainWorkDocument document) {
      LogicalFlow flow = document.flow();
      return new Draft(
          document.schemaVersion(),
          document.documentId(),
          Lists.mutable(document.sources()),
          Lists.mutable(document.requirements()),
          Lists.mutable(flow.steps()),
          Lists.mutable(flow.connections()),
          Lists.mutable(flow.sequenceGroups()),
          Lists.mutable(flow.conditionGroups()),
          Lists.mutable(flow.splitGroups()),
          Lists.mutable(flow.loopGroups()),
          Lists.mutable(flow.retryGroups()),
          Lists.mutable(flow.errorScopeGroups()),
          document.progress());
    }

    LogicalFlow flow() {
      return new LogicalFlow(
          steps,
          connections,
          sequenceGroups,
          conditionGroups,
          splitGroups,
          loopGroups,
          retryGroups,
          errorScopeGroups);
    }
  }
}
