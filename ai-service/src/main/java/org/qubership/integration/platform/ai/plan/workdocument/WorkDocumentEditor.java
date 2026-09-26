package org.qubership.integration.platform.ai.plan.workdocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
    if (state.document().schemaVersion() != ChainWorkDocument.SCHEMA_VERSION) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Document schema version must be " + ChainWorkDocument.SCHEMA_VERSION + ".");
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
    List<String> deleted = new ArrayList<>();
    applyDeletes(draft, scope, capture, known, accepted, deleted);
    WorkProgress progress = withTask(draft.progress, scope, WorkTaskState.ACCEPTED, accepted, deleted);
    if (!capture.question().isBlank()) {
      List<WorkQuestion> questions = new ArrayList<>(progress.questions());
      questions.add(question(scope, capture.unresolvedChoice(), capture.question(),
          resolveAll(capture.clarificationEvidenceIds(), aliases, evidenceIds(draft)), List.of()));
      progress = progress.replacing(progress.tasks(), progress.findings(), questions);
    }
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

  WorkDocumentState addPassages(WorkDocumentState state, String sourceId, List<SourcePassage> passages) {
    requireCurrentSchema(state);
    List<WorkSource> sources = new ArrayList<>();
    boolean found = false;
    for (WorkSource source : state.document().sources()) {
      if (!source.id().equals(sourceId)) {
        sources.add(source);
        continue;
      }
      found = true;
      List<SourcePassage> next = new ArrayList<>(source.passages());
      for (SourcePassage passage : passages) {
        validatePassage(source, passage, next);
        next.add(passage);
      }
      sources.add(
          new WorkSource(
              source.id(),
              source.role(),
              source.contentReference(),
              source.contentHash(),
              source.originalName(),
              source.suppliedIdentifier(),
              source.correctionOf(),
              source.content(),
              next));
    }
    if (!found) {
      throw reject("MALFORMED_REFERENCE", "Source " + sourceId + " does not exist. Index passages on a stored source.");
    }
    return replaceSources(state, sources);
  }

  WorkSource passageSource(WorkDocumentState state, String passageId) {
    for (WorkSource source : state.document().sources()) {
      for (SourcePassage passage : source.passages()) {
        if (passage.id().equals(passageId)) {
          return source;
        }
      }
    }
    throw reject(
        "MALFORMED_REFERENCE",
        "Passage " + passageId + " does not exist. Cite a passage indexed on a stored source.");
  }

  WorkDocumentState appendSource(WorkDocumentState state, WorkSource source) {
    requireCurrentSchema(state);
    if (source.id() == null || source.id().isBlank()) {
      throw reject("MALFORMED_REFERENCE", "A source needs an id. Assign one before appending it.");
    }
    for (WorkSource existing : state.document().sources()) {
      if (existing.id().equals(source.id())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Source " + source.id() + " already exists. Append a correction instead of replacing it.");
      }
    }
    Set<String> known = sourceIds(state.document());
    for (String corrected : source.correctionOf()) {
      if (!known.contains(corrected)) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Correction target " + corrected + " does not exist. Name a stored source.");
      }
    }
    List<WorkSource> sources = new ArrayList<>(state.document().sources());
    sources.add(source);
    return replaceSources(state, sources);
  }

  WorkCommit applyOutline(
      WorkDocumentState state, WorkTaskScope scope, OutlineProposal proposal, String commandId) {
    requireScope(state, scope);
    if (!scope.allowsCreation(WorkRecordKind.OUTLINE, proposal.targetStepId())
        && !scope.allowsCreation(WorkRecordKind.TRANSFER, proposal.targetStepId())) {
      throw reject(
          "OUTSIDE_SCOPE",
          "Outline target "
              + proposal.targetStepId()
              + " is outside this scope. Write the outline only for the assigned step.");
    }
    Draft draft = Draft.from(state.document());
    if (find(draft.steps, proposal.targetStepId(), LogicalStep::id) == null) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Step " + proposal.targetStepId() + " does not exist. Outline an existing target step.");
    }
    Map<String, String> aliases = new LinkedHashMap<>();
    Set<String> known = knownIds(state.document());
    List<String> accepted = new ArrayList<>();
    List<String> transferIds = new ArrayList<>();
    List<String> requirementIds = new ArrayList<>();
    for (OutlineRetained retained : proposal.retainedPlaceholders()) {
      if (!scope.allowsCreation(WorkRecordKind.RETAINED_VALUE, retained.producerStepId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Retained placeholder under "
                + retained.producerStepId()
                + " is outside this scope. Create placeholders only for an assigned producer.");
      }
      if (find(draft.steps, retained.producerStepId(), LogicalStep::id) == null) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Producer " + retained.producerStepId() + " does not exist. Name an existing step.");
      }
      if (!retained.existingId().isBlank()
          && retainedOnProducer(draft, retained.producerStepId(), retained.existingId())
          && !scope.allowsReplacement(retained.existingId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Retained value "
                + retained.existingId()
                + " is outside the assigned scope. Update only an assigned retained value.");
      }
      resolveAll(retained.evidenceIds(), aliases, evidenceIds(draft));
      String id =
          allocateOutlineRecord(
              aliases,
              known,
              retained.existingId(),
              retained.alias(),
              retainedOnProducer(draft, retained.producerStepId(), retained.existingId()),
              "Record "
                  + retained.existingId()
                  + " is not a retained value on "
                  + retained.producerStepId()
                  + ". Reuse only a retained value from that producer.");
      RetainedValue previous = retainedOn(draft, id);
      boolean sameObligation =
          previous != null
              && previous.intendedUse().equals(retained.intendedUse())
              && previous.evidenceIds().equals(retained.evidenceIds())
              && previous.producerStepId().equals(retained.producerStepId());
      RetainedValue stored =
          sameObligation
              ? previous
              : new RetainedValue(
                  id,
                  null,
                  retained.intendedUse(),
                  retained.evidenceIds(),
                  retained.producerStepId(),
                  RetainedResolution.UNRESOLVED);
      replaceRetained(draft, retained.producerStepId(), stored);
      known.add(id);
      accepted.add(id);
    }
    for (OutlineTransfer captured : proposal.transfers()) {
      if (captured.existingId().isBlank()
          && !scope.allowsCreation(WorkRecordKind.TRANSFER, proposal.targetStepId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Transfer creation under "
                + proposal.targetStepId()
                + " is outside this scope. Assign that parent before creating a transfer.");
      }
      List<PortRef> sources = new ArrayList<>();
      for (PortRef port : captured.sourcePorts()) {
        sources.add(checkedPort(draft, aliases, port));
      }
      PortRef target = checkedPort(draft, aliases, captured.targetPort());
      if (!proposal.targetStepId().equals(target.stepId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Transfer target is outside step " + proposal.targetStepId() + ". Keep the assigned target.");
      }
      if (!captured.existingId().isBlank()
          && transferOnStep(draft, proposal.targetStepId(), captured.existingId())
          && !scope.allowsReplacement(captured.existingId())) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Transfer "
                + captured.existingId()
                + " is outside the assigned scope. Update only an assigned transfer.");
      }
      String id =
          allocateOutlineRecord(
              aliases,
              known,
              captured.existingId(),
              captured.alias(),
              transferOnStep(draft, proposal.targetStepId(), captured.existingId()),
              "Record "
                  + captured.existingId()
                  + " is not a transfer on "
                  + proposal.targetStepId()
                  + ". Reuse only a transfer that already belongs to this target.");
      List<String> retainedIds = resolveAll(captured.requiredRetainedIds(), aliases, known);
      List<String> resolvedRequirements = resolveAll(captured.requirementIds(), aliases, known);
      DataTransfer previous = findTransfer(draft, id);
      DataTransfer stored =
          new DataTransfer(
              id,
              sources,
              target,
              resolvedRequirements,
              previous == null ? List.of() : previous.rules(),
              captured.decision().isBlank() && previous != null
                  ? previous.decision()
                  : decision(captured.decision()),
              captured.outcome(),
              retainedIds);
      replaceTransfer(draft, proposal.targetStepId(), stored);
      transferIds.add(id);
      known.add(id);
      accepted.add(id);
      for (String requirementId : resolvedRequirements) {
        if (!requirementIds.contains(requirementId)) {
          requirementIds.add(requirementId);
        }
      }
    }
    LogicalStep current = find(draft.steps, proposal.targetStepId(), LogicalStep::id);
    java.util.Map<String, CoverageEntry> coverage = new java.util.LinkedHashMap<>();
    for (CoverageEntry existing : current.data().outline().coverage()) {
      coverage.put(existing.requirementId() + "\n" + existing.passageId(), existing);
      if (!existing.requirementId().isBlank() && !requirementIds.contains(existing.requirementId())) {
        requirementIds.add(existing.requirementId());
      }
    }
    for (OutlineCoverage entry : proposal.coverage()) {
      if (!entry.passageId().isBlank()) {
        passageSource(WorkDocumentState.of(documentFrom(draft, state.document().progress())), entry.passageId());
      }
      if (!entry.requirementId().isBlank() && !requirementIds.contains(entry.requirementId())) {
        requirementIds.add(entry.requirementId());
      }
      coverage.put(
          entry.requirementId() + "\n" + entry.passageId(),
          new CoverageEntry(entry.requirementId(), entry.passageId(), entry.disposition()));
    }
    List<String> storedTransferIds = new ArrayList<>();
    for (DataTransfer transfer : current.data().transfers()) {
      storedTransferIds.add(transfer.id());
      for (String requirementId : transfer.requirementIds()) {
        if (!requirementIds.contains(requirementId)) {
          requirementIds.add(requirementId);
        }
      }
    }
    DataOutline outline = new DataOutline(requirementIds, storedTransferIds, new ArrayList<>(coverage.values()));
    for (int i = 0; i < draft.steps.size(); i++) {
      LogicalStep step = draft.steps.get(i);
      if (step.id().equals(proposal.targetStepId())) {
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
                step.data().withOutline(outline)));
      }
    }
    WorkProgress progress = withTask(draft.progress, scope, WorkTaskState.ACCEPTED, accepted);
    WorkDocumentState committed = WorkDocumentState.of(documentFrom(draft, progress));
    return new WorkCommit(
        committed.revision(), List.copyOf(accepted), WorkOutcome.PREPARED, commandId, committed, Map.copyOf(aliases));
  }

  WorkCommit recordQuestion(
      WorkDocumentState state,
      WorkTaskScope scope,
      String questionText,
      QuestionSubject subject,
      List<String> blockedRecordIds,
      List<String> evidenceIds,
      String commandId) {
    requireScope(state, scope);
    resolveAll(evidenceIds, Map.of(), evidenceIds(state.document()));
    resolveAll(blockedRecordIds, Map.of(), knownIds(state.document()));
    WorkProgress prior = state.document().progress();
    List<WorkQuestion> questions = Lists.mutable(prior.questions());
    questions.add(
        new WorkQuestion(
            newId(),
            subject.choiceKind().name(),
            questionText,
            evidenceIds,
            scope.taskKey(),
            subject,
            blockedRecordIds,
            List.of(),
            QuestionResolution.OPEN));
    WorkProgress progress =
        withTask(
            prior.replacing(prior.tasks(), prior.findings(), questions),
            scope,
            WorkTaskState.NEEDS_INPUT,
            List.of());
    ChainWorkDocument next = documentFrom(Draft.from(state.document()), progress);
    WorkDocumentState committed = WorkDocumentState.of(next);
    return new WorkCommit(
        committed.revision(), List.of(), WorkOutcome.NEEDS_CLARIFICATION, commandId, committed, Map.of());
  }

  WorkCommit linkInput(
      WorkDocumentState state,
      String questionId,
      String inputId,
      String text,
      String contentHash,
      String contentReference,
      String commandId) {
    requireCurrentSchema(state);
    WorkQuestion question = null;
    for (WorkQuestion candidate : state.document().progress().questions()) {
      if (candidate.id().equals(questionId)) {
        question = candidate;
      }
    }
    if (question == null) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Question " + questionId + " does not exist. Answer a question the document already stores.");
    }
    if (question.ownerTaskKey().isBlank()) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Question " + questionId + " has no owner. Record the question against a task before answering it.");
    }
    String sourceId = "src-" + inputId;
    List<WorkSource> sources = new ArrayList<>();
    boolean linked = false;
    for (WorkSource existing : state.document().sources()) {
      if (inputId.equals(existing.suppliedIdentifier())) {
        if (!contentHash.equals(existing.contentHash())) {
          throw reject(
              "CONFLICTING_INPUT",
              "Input " + inputId + " is already stored with different text. Reuse the original text.");
        }
        sourceId = existing.id();
        linked = true;
      }
      sources.add(existing);
    }
    if (!linked) {
      if (sourceIdTakenByOther(sources, sourceId, inputId)) {
        sourceId = freshSourceId(sources);
      }
      sources.add(
          new WorkSource(
              sourceId,
              "answer",
              contentReference,
              contentHash,
              "answer.txt",
              inputId,
              List.of(),
              text,
              List.of()));
    }
    List<String> answers = new ArrayList<>(question.answerSourceIds());
    if (!answers.contains(sourceId)) {
      answers.add(sourceId);
    }
    WorkQuestion updated =
        new WorkQuestion(
            question.id(),
            question.choice(),
            question.question(),
            question.evidenceIds(),
            question.ownerTaskKey(),
            question.subject(),
            question.blockedRecordIds(),
            answers,
            QuestionResolution.ANSWERED);
    List<WorkQuestion> questions = new ArrayList<>();
    for (WorkQuestion candidate : state.document().progress().questions()) {
      questions.add(candidate.id().equals(questionId) ? updated : candidate);
    }
    WorkProgress prior = state.document().progress();
    List<WorkTaskRecord> tasks = new ArrayList<>();
    boolean ownerFound = false;
    for (WorkTaskRecord task : prior.tasks()) {
      if (task.taskKey().equals(question.ownerTaskKey())) {
        ownerFound = true;
        tasks.add(
            new WorkTaskRecord(
                task.taskKey(),
                task.kind(),
                task.taskId(),
                WorkTaskState.NEEDS_RECHECK,
                task.stage(),
                task.skillId(),
                task.acceptedInputFingerprint(),
                task.producedRecordIds()));
      } else {
        tasks.add(task);
      }
    }
    if (!ownerFound) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Owner " + question.ownerTaskKey() + " does not exist. Reopen a task the document already stores.");
    }
    WorkProgress progress = prior.replacing(tasks, prior.findings(), questions);
    ChainWorkDocument next =
        new ChainWorkDocument(
            state.document().schemaVersion(),
            state.document().documentId(),
            sources,
            state.document().requirements(),
            state.document().flow(),
            progress);
    WorkDocumentState committed = WorkDocumentState.of(next);
    return new WorkCommit(
        committed.revision(), List.of(sourceId), WorkOutcome.PREPARED, commandId, committed, Map.of());
  }

  private static void validatePassage(WorkSource source, SourcePassage passage, List<SourcePassage> existing) {
    if (passage.id().isBlank() || !source.id().equals(passage.sourceId())) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Passage " + passage.id() + " does not belong to source " + source.id() + ". Keep the server-assigned source.");
    }
    if (!sha256(passage.text()).equals(passage.contentHash())) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Passage " + passage.id() + " hash does not match its text. Reindex the passage from the stored source.");
    }
    if (!source.content().contains(passage.text())) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Passage " + passage.id() + " is not in source " + source.id() + ". Index text that the source already stores.");
    }
    for (SourcePassage prior : existing) {
      if (prior.id().equals(passage.id())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Passage " + passage.id() + " already exists. Append a new passage instead of replacing it.");
      }
    }
  }

  private static String allocateOutlineRecord(
      Map<String, String> aliases,
      Set<String> known,
      String existingId,
      String alias,
      boolean legalExisting,
      String illegalExistingMessage) {
    boolean hasExisting = existingId != null && !existingId.isBlank();
    boolean hasAlias = alias != null && !alias.isBlank();
    if (hasExisting == hasAlias) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Provide an alias for a new record or an existing id for a replacement, not both.");
    }
    if (hasExisting) {
      if (!legalExisting) {
        throw reject("MALFORMED_REFERENCE", illegalExistingMessage);
      }
      return existingId;
    }
    assignOne(aliases, known, "", alias);
    return aliases.get(alias);
  }

  private static boolean transferOnStep(Draft draft, String stepId, String transferId) {
    if (transferId == null || transferId.isBlank()) {
      return false;
    }
    LogicalStep step = find(draft.steps, stepId, LogicalStep::id);
    if (step == null) {
      return false;
    }
    for (DataTransfer transfer : step.data().transfers()) {
      if (transfer.id().equals(transferId)) {
        return true;
      }
    }
    return false;
  }

  private static RetainedValue retainedOn(Draft draft, String retainedId) {
    for (LogicalStep step : draft.steps) {
      for (RetainedValue value : step.data().retainedValues()) {
        if (value.id().equals(retainedId)) {
          return value;
        }
      }
    }
    return null;
  }

  private static boolean retainedOnProducer(Draft draft, String producerStepId, String retainedId) {
    if (retainedId == null || retainedId.isBlank()) {
      return false;
    }
    LogicalStep step = find(draft.steps, producerStepId, LogicalStep::id);
    if (step == null) {
      return false;
    }
    for (RetainedValue value : step.data().retainedValues()) {
      if (value.id().equals(retainedId)) {
        return true;
      }
    }
    return false;
  }

  private static boolean sourceIdTakenByOther(List<WorkSource> sources, String sourceId, String inputId) {
    for (WorkSource source : sources) {
      if (source.id().equals(sourceId) && !inputId.equals(source.suppliedIdentifier())) {
        return true;
      }
    }
    return false;
  }

  private static String freshSourceId(List<WorkSource> sources) {
    String id = newId();
    while (containsSourceId(sources, id)) {
      id = newId();
    }
    return id;
  }

  private static boolean containsSourceId(List<WorkSource> sources, String sourceId) {
    for (WorkSource source : sources) {
      if (source.id().equals(sourceId)) {
        return true;
      }
    }
    return false;
  }

  private static void requireScope(WorkDocumentState state, WorkTaskScope scope) {
    requireCurrentSchema(state);
    if (!scope.baseRevision().equals(state.revision())) {
      throw reject(
          "STALE_SCOPE",
          "Scope revision does not match the current document. Read the document and submit the capture again.");
    }
  }

  private static void requireCurrentSchema(WorkDocumentState state) {
    if (state.document().schemaVersion() != ChainWorkDocument.SCHEMA_VERSION) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Document schema version must be " + ChainWorkDocument.SCHEMA_VERSION + ".");
    }
  }

  private static WorkDocumentState replaceSources(WorkDocumentState state, List<WorkSource> sources) {
    ChainWorkDocument document = state.document();
    return WorkDocumentState.of(
        new ChainWorkDocument(
            document.schemaVersion(),
            document.documentId(),
            sources,
            document.requirements(),
            document.flow(),
            document.progress()));
  }

  private static ChainWorkDocument documentFrom(Draft draft, WorkProgress progress) {
    return new ChainWorkDocument(
        draft.schemaVersion, draft.documentId, draft.sources, draft.requirements, draft.flow(), progress);
  }

  private static String sha256(String content) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
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
          question(
              scope,
              capture.unresolvedChoice(),
              capture.question(),
              resolveAll(capture.clarificationEvidenceIds(), Map.of(), evidenceIds(state.document())),
              List.of()));
    } else {
      taskState = WorkTaskState.NEEDS_RECHECK;
      findings.add(
          new WorkFinding(
              newId(),
              resolve(capture.defectRecordRef(), Map.of(), knownIds(state.document()), true),
              capture.issueCategory(),
              capture.contradiction(),
              resolveAll(capture.defectEvidenceIds(), Map.of(), evidenceIds(state.document()))));
    }
    WorkProgress progress =
        withTask(prior.replacing(prior.tasks(), findings, questions), scope, taskState, List.of());
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
    if (capture.outcome() == WorkOutcome.PREPARED && defect) {
      throw reject(
          "CONTRADICTORY_OUTCOME",
          "A prepared capture cannot also report a defect. Send one outcome.");
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
      String id = permit(scope, requirement.existingId(), requirement.alias(), aliases, WorkRecordKind.REQUIREMENT, "");
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
      String id = permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.STEP, "");
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
      String id = permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.CONNECTION, "");
      LogicalConnection stored =
          new LogicalConnection(
              id,
              resolve(captured.sourceStepRef(), aliases, steps, true),
              captured.outcome(),
              resolve(captured.targetStepRef(), aliases, steps, true),
              captured.routingIntent(),
              resolveAll(captured.evidenceRefs(), aliases, evidenceIds(draft)));
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
      String id =
          permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.SEQUENCE_GROUP, "");
      upsert(
          draft.sequenceGroups,
          id,
          new SequenceGroup(id, resolveAll(captured.memberStepRefs(), aliases, steps)),
          SequenceGroup::id);
      known.add(id);
      accepted.add(id);
    }
    for (CapturedConditionGroup captured : capture.conditionGroups()) {
      String id =
          permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.CONDITION_GROUP, "");
      ConditionGroup previous = find(draft.conditionGroups, id, ConditionGroup::id);
      List<ConditionBranch> branches = new ArrayList<>();
      if (previous != null) {
        branches.addAll(previous.branches());
      }
      List<String> placed = new ArrayList<>();
      for (CapturedConditionBranch branch : captured.branches()) {
        String branchId =
            permit(scope, branch.existingId(), branch.alias(), aliases, WorkRecordKind.CONDITION_GROUP, id);
        upsert(
            branches,
            branchId,
            new ConditionBranch(
                branchId,
                branch.role(),
                branch.predicate(),
                branch.priority(),
                resolve(branch.entryStepRef(), aliases, steps, true),
                resolveAll(branch.exitStepRefs(), aliases, steps)),
            ConditionBranch::id);
        placed.add(branchId);
        known.add(branchId);
        accepted.add(branchId);
      }
      for (String branchId : placed) {
        stripNested(draft.conditionGroups, branchId);
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
      String id = permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.SPLIT_GROUP, "");
      SplitGroup previous = find(draft.splitGroups, id, SplitGroup::id);
      List<SplitBranch> branches = new ArrayList<>();
      if (previous != null) {
        branches.addAll(previous.branches());
      }
      List<String> placed = new ArrayList<>();
      for (CapturedSplitBranch branch : captured.branches()) {
        String branchId =
            permit(scope, branch.existingId(), branch.alias(), aliases, WorkRecordKind.SPLIT_GROUP, id);
        upsert(
            branches,
            branchId,
            new SplitBranch(
                branchId,
                branch.order(),
                resolve(branch.entryStepRef(), aliases, steps, true),
                resolveAll(branch.exitStepRefs(), aliases, steps)),
            SplitBranch::id);
        placed.add(branchId);
        known.add(branchId);
        accepted.add(branchId);
      }
      for (String branchId : placed) {
        stripNestedSplits(draft.splitGroups, branchId);
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
      String id = permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.LOOP_GROUP, "");
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
      String id = permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.RETRY_GROUP, "");
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
      String id = permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.ERROR_SCOPE, "");
      ErrorScopeGroup previous = find(draft.errorScopeGroups, id, ErrorScopeGroup::id);
      List<ErrorHandler> handlers = new ArrayList<>();
      if (previous != null) {
        handlers.addAll(previous.handlers());
      }
      List<String> placed = new ArrayList<>();
      for (CapturedErrorHandler handler : captured.handlers()) {
        String handlerId =
            permit(scope, handler.existingId(), handler.alias(), aliases, WorkRecordKind.ERROR_SCOPE, id);
        upsert(
            handlers,
            handlerId,
            new ErrorHandler(
                handlerId,
                handler.exceptionClass(),
                resolve(handler.entryStepRef(), aliases, steps, true),
                resolveAll(handler.exitStepRefs(), aliases, steps)),
            ErrorHandler::id);
        placed.add(handlerId);
        known.add(handlerId);
        accepted.add(handlerId);
      }
      for (String handlerId : placed) {
        stripNestedHandlers(draft.errorScopeGroups, handlerId);
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
      if (scope.fixedEndpoint() != null) {
        throw reject(
            "OUTSIDE_SCOPE",
            "This mapping scope cannot create or replace a transfer. The endpoint and outcome stay fixed.");
      }
      String targetStep = resolve(captured.targetStepRef(), aliases, ids(draft.steps, LogicalStep::id), true);
      String id =
          permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.TRANSFER, targetStep);
      requireReplacementParent(
          scope,
          captured.existingId(),
          WorkRecordKind.TRANSFER,
          targetStep,
          parentStep(draft, captured.existingId(), WorkRecordKind.TRANSFER));
      List<PortRef> sources = new ArrayList<>();
      for (PortRef port : captured.sourcePorts()) {
        sources.add(checkedPort(draft, aliases, port));
      }
      PortRef target = checkedPort(draft, aliases, captured.targetPort());
      DataTransfer previous = findTransfer(draft, id);
      List<String> evidence =
          captured.evidenceRefs() == null || captured.evidenceRefs().isEmpty()
              ? (previous == null ? List.of() : previous.evidenceIds())
              : resolveAll(captured.evidenceRefs(), aliases, evidenceIds(draft));
      DataTransfer stored =
          new DataTransfer(
              id,
              sources,
              target,
              resolveAll(captured.requirementRefs(), aliases, known),
              previous == null ? List.of() : previous.rules(),
              decision(captured.decision()),
              previous == null ? TransferOutcome.UNSPECIFIED : previous.outcome(),
              previous == null ? List.of() : previous.requiredRetainedIds(),
              evidence);
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
      String transferId = resolve(captured.transferRef(), aliases, known, true);
      requireFixedTransfer(scope, transferId);
      String id = permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.RULE, transferId);
      requireReplacementParent(
          scope, captured.existingId(), WorkRecordKind.RULE, transferId, parentTransfer(draft, captured.existingId()));
      List<FieldReference> sources = new ArrayList<>();
      for (FieldReference source : captured.sources()) {
        sources.add(checkedField(draft, aliases, known, source));
      }
      FieldReference target = checkedField(draft, aliases, known, captured.target());
      requireFixedPort(scope, target);
      MappingRule stored =
          new MappingRule(
              id,
              sources,
              target,
              captured.constants(),
              captured.behavior(),
              resolveAll(captured.evidenceRefs(), aliases, evidenceIds(draft)));
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
      if (scope.fixedEndpoint() != null) {
        throw reject(
            "OUTSIDE_SCOPE",
            "This mapping scope cannot create or replace a retained value. Update only the assigned transfer rules.");
      }
      String stepId = resolve(captured.stepRef(), aliases, ids(draft.steps, LogicalStep::id), true);
      String id =
          permit(scope, captured.existingId(), captured.alias(), aliases, WorkRecordKind.RETAINED_VALUE, stepId);
      requireReplacementParent(
          scope,
          captured.existingId(),
          WorkRecordKind.RETAINED_VALUE,
          stepId,
          parentStep(draft, captured.existingId(), WorkRecordKind.RETAINED_VALUE));
      FieldReference source = checkedField(draft, aliases, known, captured.source());
      RetainedValue stored =
          new RetainedValue(
              id,
              source,
              captured.intendedUse(),
              resolveAll(captured.evidenceRefs(), aliases, evidenceIds(draft)),
              stepId,
              RetainedResolution.RESOLVED);
      replaceRetained(draft, stepId, stored);
      known.add(id);
      accepted.add(id);
    }
  }

  private static void applyDeletes(
      Draft draft,
      WorkTaskScope scope,
      WorkTaskCapture capture,
      Set<String> known,
      List<String> accepted,
      List<String> deleted) {
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
      resolveAll(delete.evidenceRefs(), Map.of(), evidenceIds(draft));
      remove(draft, delete.existingId());
      if (stillReferenced(draft, delete.existingId())) {
        throw reject(
            "MALFORMED_REFERENCE",
            "Deleting " + delete.existingId() + " leaves a dangling reference. Delete or retarget the dependent records in this capture.");
      }
      known.remove(delete.existingId());
      deleted.add(delete.existingId());
      accepted.add(delete.existingId());
    }
  }

  private static String permit(
      WorkTaskScope scope,
      String existingId,
      String alias,
      Map<String, String> aliases,
      WorkRecordKind kind,
      String parentId) {
    boolean hasExisting = existingId != null && !existingId.isBlank();
    boolean hasAlias = alias != null && !alias.isBlank();
    if (hasExisting == hasAlias) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Provide an alias for a new record or an existing id for a replacement, not both.");
    }
    if (hasAlias) {
      if (!scope.createPermitted() || !scope.allowsCreation(kind, parentId)) {
        throw reject(
            "OUTSIDE_SCOPE",
            "Creation of "
                + kind
                + " is outside this scope. Assign that record kind and parent before creating it.");
      }
      return aliases.get(alias);
    }
    if (!scope.allowsReplacement(existingId)) {
      throw reject(
          "OUTSIDE_SCOPE",
          "Record " + existingId + " is outside the assigned scope. Submit only records this task owns.");
    }
    boolean kindLimited =
        scope.creationAllowances().stream().anyMatch(allowance -> allowance.kind() == kind);
    if (kindLimited && !scope.allowsCreation(kind, parentId)) {
      throw reject(
          "OUTSIDE_SCOPE",
          "Record "
              + existingId
              + " is outside the assigned parent. Update only records under the assigned parent.");
    }
    return existingId;
  }

  private static void requireFixedTransfer(WorkTaskScope scope, String transferId) {
    FixedTransferEndpoint fixed = scope.fixedEndpoint();
    if (fixed != null && !fixed.transferId().equals(transferId)) {
      throw reject(
          "OUTSIDE_SCOPE",
          "Transfer "
              + transferId
              + " is outside this mapping scope. Write rules only for "
              + fixed.transferId()
              + ".");
    }
  }

  private static void requireFixedPort(WorkTaskScope scope, FieldReference target) {
    FixedTransferEndpoint fixed = scope.fixedEndpoint();
    if (fixed == null) {
      return;
    }
    String stepId = target == null || target.stepId() == null ? "" : target.stepId();
    String portName = target == null || target.port() == null ? "" : target.port().schemaName();
    if (target == null
        || target.kind() != FieldReferenceKind.STEP_PORT
        || target.port() == null
        || !fixed.targetPort().stepId().equals(stepId)
        || !fixed.targetPort().portName().equals(portName)) {
      throw reject(
          "OUTSIDE_SCOPE",
          "Rule target "
              + stepId
              + " "
              + portName
              + " does not match the fixed endpoint. Keep the assigned port.");
    }
  }

  private static void requireReplacementParent(
      WorkTaskScope scope, String existingId, WorkRecordKind kind, String parentId, String currentParent) {
    if (existingId == null || existingId.isBlank()) {
      return;
    }
    if (currentParent == null) {
      throw reject(
          "MALFORMED_REFERENCE",
          "Record " + existingId + " is not a " + kind + ". Replace only an existing " + kind + ".");
    }
    if (currentParent.equals(parentId)) {
      return;
    }
    if (scope.allowsCreation(kind, parentId)) {
      return;
    }
    throw reject(
        "OUTSIDE_SCOPE",
        "Record "
            + existingId
            + " belongs to "
            + currentParent
            + ". Keep that parent, or use a scope that lists "
            + parentId
            + ".");
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

  private static Set<String> evidenceIds(Draft draft) {
    Set<String> evidence = new LinkedHashSet<>(sourceIds(draft));
    for (WorkSource source : draft.sources) {
      for (SourcePassage passage : source.passages()) {
        evidence.add(passage.id());
      }
    }
    return evidence;
  }

  private static Set<String> evidenceIds(ChainWorkDocument document) {
    Set<String> evidence = new LinkedHashSet<>(sourceIds(document));
    for (WorkSource source : document.sources()) {
      for (SourcePassage passage : source.passages()) {
        evidence.add(passage.id());
      }
    }
    return evidence;
  }

  private static String newId() {
    return "wd-" + UUID.randomUUID();
  }

  private static WorkProgress withTask(
      WorkProgress progress, WorkTaskScope scope, WorkTaskState state, List<String> producedRecordIds) {
    return withTask(progress, scope, state, producedRecordIds, List.of());
  }

  private static WorkProgress withTask(
      WorkProgress progress,
      WorkTaskScope scope,
      WorkTaskState state,
      List<String> producedRecordIds,
      List<String> deletedIds) {
    List<WorkTaskRecord> tasks = new ArrayList<>();
    WorkTaskRecord previous = null;
    boolean replaced = false;
    WorkTaskRecord next = taskRow(scope, state, producedRecordIds, deletedIds, null);
    for (WorkTaskRecord existing : progress.tasks()) {
      if (existing.taskKey().equals(scope.taskKey())) {
        previous = existing;
        tasks.add(taskRow(scope, state, producedRecordIds, deletedIds, existing));
        replaced = true;
      } else {
        tasks.add(existing);
      }
    }
    if (!replaced) {
      tasks.add(previous == null ? next : taskRow(scope, state, producedRecordIds, deletedIds, previous));
    }
    return progress.replacing(tasks, progress.findings(), progress.questions());
  }

  private static WorkTaskRecord taskRow(
      WorkTaskScope scope,
      WorkTaskState state,
      List<String> producedRecordIds,
      List<String> deletedIds,
      WorkTaskRecord previous) {
    String fingerprint = previous == null ? "" : previous.acceptedInputFingerprint();
    if (state == WorkTaskState.ACCEPTED && !scope.inputFingerprint().isBlank()) {
      fingerprint = scope.inputFingerprint();
    }
    List<String> produced;
    if (state == WorkTaskState.ACCEPTED) {
      LinkedHashSet<String> union = new LinkedHashSet<>();
      if (previous != null) {
        union.addAll(previous.producedRecordIds());
      }
      union.addAll(producedRecordIds);
      union.removeAll(deletedIds);
      produced = List.copyOf(union);
    } else {
      produced = previous == null ? List.of() : previous.producedRecordIds();
    }
    return new WorkTaskRecord(
        scope.taskKey(),
        scope.taskKind(),
        scope.taskId(),
        state,
        scope.stage(),
        scope.skillId(),
        fingerprint,
        produced);
  }

  private static String parentTransfer(Draft draft, String ruleId) {
    return parentStep(draft, ruleId, WorkRecordKind.RULE);
  }

  private static String parentStep(Draft draft, String recordId, WorkRecordKind kind) {
    if (recordId == null || recordId.isBlank()) {
      return null;
    }
    for (LogicalStep step : draft.steps) {
      if (kind == WorkRecordKind.TRANSFER) {
        for (DataTransfer transfer : step.data().transfers()) {
          if (transfer.id().equals(recordId)) {
            return step.id();
          }
        }
      } else if (kind == WorkRecordKind.RETAINED_VALUE) {
        for (RetainedValue value : step.data().retainedValues()) {
          if (value.id().equals(recordId)) {
            return step.id();
          }
        }
      } else if (kind == WorkRecordKind.RULE) {
        for (DataTransfer transfer : step.data().transfers()) {
          for (MappingRule rule : transfer.rules()) {
            if (rule.id().equals(recordId)) {
              return transfer.id();
            }
          }
        }
      }
    }
    return null;
  }

  private static WorkQuestion question(
      WorkTaskScope scope,
      String choice,
      String text,
      List<String> evidenceIds,
      List<String> blockedRecordIds) {
    return new WorkQuestion(
        newId(),
        choice,
        text,
        evidenceIds,
        scope.taskKey(),
        QuestionSubject.unspecified(),
        blockedRecordIds,
        List.of(),
        QuestionResolution.OPEN);
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
      boolean removed = false;
      for (DataTransfer transfer : step.data().transfers()) {
        if (transfer.id().equals(stored.id())) {
          if (!onStep) {
            removed = true;
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
      if (onStep || replaced || removed) {
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
                step.data().withTransfers(transfers)));
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
          List<MappingRule> rules = new ArrayList<>();
          boolean cleared = false;
          for (MappingRule rule : transfer.rules()) {
            if (rule.id().equals(stored.id())) {
              cleared = true;
            } else {
              rules.add(rule);
            }
          }
          if (cleared) {
            stepChanged = true;
            transfers.add(transfer.withRules(rules));
          } else {
            transfers.add(transfer);
          }
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
        transfers.add(transfer.withRules(rules));
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
                step.data().withTransfers(transfers)));
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
      boolean onStep = step.id().equals(stepId);
      List<RetainedValue> values = new ArrayList<>();
      boolean replaced = false;
      boolean removed = false;
      for (RetainedValue value : step.data().retainedValues()) {
        if (value.id().equals(stored.id())) {
          if (!onStep) {
            removed = true;
            continue;
          }
          values.add(stored);
          replaced = true;
        } else {
          values.add(value);
        }
      }
      if (onStep && !replaced) {
        values.add(stored);
      }
      if (onStep || replaced || removed) {
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
                step.data().withRetained(values)));
      }
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
    stripNested(draft.conditionGroups, id);
    stripNestedSplits(draft.splitGroups, id);
    stripNestedHandlers(draft.errorScopeGroups, id);
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
          transfers.add(transfer.withRules(rules));
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
                step.data().withTransfers(transfers).withRetained(retained)));
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
        for (MappingRule rule : transfer.rules()) {
          if (fieldReferences(rule, id)) {
            return true;
          }
        }
      }
      for (RetainedValue value : step.data().retainedValues()) {
        if (value.source() != null && fieldPointsAt(value.source(), id)) {
          return true;
        }
      }
    }
    for (SequenceGroup group : draft.sequenceGroups) {
      if (group.memberStepIds().contains(id)) {
        return true;
      }
    }
    for (ConditionGroup group : draft.conditionGroups) {
      if (id.equals(group.ownerStepId()) || id.equals(group.reconvergenceStepId())) {
        return true;
      }
      for (ConditionBranch branch : group.branches()) {
        if (id.equals(branch.entryStepId()) || branch.exitStepIds().contains(id)) {
          return true;
        }
      }
    }
    for (SplitGroup group : draft.splitGroups) {
      if (id.equals(group.ownerStepId()) || id.equals(group.reconvergenceStepId())) {
        return true;
      }
      for (SplitBranch branch : group.branches()) {
        if (id.equals(branch.entryStepId()) || branch.exitStepIds().contains(id)) {
          return true;
        }
      }
    }
    for (LoopGroup group : draft.loopGroups) {
      if (id.equals(group.ownerStepId())
          || id.equals(group.bodyEntryStepId())
          || id.equals(group.exitStepId())
          || group.bodyExitStepIds().contains(id)) {
        return true;
      }
    }
    for (RetryGroup group : draft.retryGroups) {
      if (id.equals(group.ownerStepId())
          || id.equals(group.bodyEntryStepId())
          || id.equals(group.exhaustedStepId())
          || group.bodyExitStepIds().contains(id)) {
        return true;
      }
    }
    for (ErrorScopeGroup group : draft.errorScopeGroups) {
      if (id.equals(group.ownerStepId())
          || id.equals(group.tryEntryStepId())
          || id.equals(group.finallyEntryStepId())
          || group.exitStepIds().contains(id)) {
        return true;
      }
      for (ErrorHandler handler : group.handlers()) {
        if (id.equals(handler.entryStepId()) || handler.exitStepIds().contains(id)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void stripNested(List<ConditionGroup> groups, String id) {
    for (int i = 0; i < groups.size(); i++) {
      ConditionGroup group = groups.get(i);
      List<ConditionBranch> branches = new ArrayList<>();
      boolean removed = false;
      for (ConditionBranch branch : group.branches()) {
        if (branch.id().equals(id)) {
          removed = true;
        } else {
          branches.add(branch);
        }
      }
      if (removed) {
        groups.set(i, new ConditionGroup(group.id(), group.ownerStepId(), branches, group.reconvergenceStepId()));
      }
    }
  }

  private static void stripNestedSplits(List<SplitGroup> groups, String id) {
    for (int i = 0; i < groups.size(); i++) {
      SplitGroup group = groups.get(i);
      List<SplitBranch> branches = new ArrayList<>();
      boolean removed = false;
      for (SplitBranch branch : group.branches()) {
        if (branch.id().equals(id)) {
          removed = true;
        } else {
          branches.add(branch);
        }
      }
      if (removed) {
        groups.set(
            i,
            new SplitGroup(group.id(), group.ownerStepId(), group.mode(), branches, group.reconvergenceStepId()));
      }
    }
  }

  private static void stripNestedHandlers(List<ErrorScopeGroup> groups, String id) {
    for (int i = 0; i < groups.size(); i++) {
      ErrorScopeGroup group = groups.get(i);
      List<ErrorHandler> handlers = new ArrayList<>();
      boolean removed = false;
      for (ErrorHandler handler : group.handlers()) {
        if (handler.id().equals(id)) {
          removed = true;
        } else {
          handlers.add(handler);
        }
      }
      if (removed) {
        groups.set(
            i,
            new ErrorScopeGroup(
                group.id(),
                group.ownerStepId(),
                group.tryEntryStepId(),
                handlers,
                group.finallyEntryStepId(),
                group.exitStepIds()));
      }
    }
  }

  private static boolean fieldReferences(MappingRule rule, String id) {
    if (rule.target() != null && fieldPointsAt(rule.target(), id)) {
      return true;
    }
    for (FieldReference source : rule.sources()) {
      if (fieldPointsAt(source, id)) {
        return true;
      }
    }
    return false;
  }

  private static boolean fieldPointsAt(FieldReference reference, String id) {
    return id.equals(reference.stepId()) || id.equals(reference.retainedValueId());
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
