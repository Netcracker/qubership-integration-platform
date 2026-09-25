package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Clock;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import org.qubership.integration.platform.ai.plan.workdocument.binding.CatalogResolution;
import org.qubership.integration.platform.ai.plan.workdocument.binding.ContractMaterial;
import org.qubership.integration.platform.ai.plan.workdocument.binding.PortSchemaMaterial;
import org.qubership.integration.platform.ai.plan.workdocument.binding.WorkBinding;
import org.qubership.integration.platform.ai.plan.workdocument.flow.WorkLogicalFlow;
import org.qubership.integration.platform.ai.plan.workdocument.mapping.WorkMapping;
import org.qubership.integration.platform.ai.plan.workdocument.recovery.WorkRecovery;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore.ProviderDeliveryOutcome;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;

/**
 * One advance or one answer. Derives the next task from the committed document and calls the
 * existing handlers. A repeated command returns the stored result.
 */
public final class WorkDocumentFilling {

  static final String CORRECTIVE_KEY = "corrective-target";
  private static final String ADVANCE_PAYLOAD = "advance";
  private static final String DISPATCH_PREFIX = "filling-dispatch:";
  private static final String EXHAUSTED_PREFIX = "recovery-exhausted:";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final WorkDocumentService documents;
  private final ProductPipelineRunStore runs;
  private final WorkTaskModel model;
  private final CatalogResolution catalog;
  private final WorkRecovery recovery;
  private final RetryPolicy retry;
  private final WorkTaskPlanner planner = new WorkTaskPlanner();
  private final WorkLogicalFlow logical;
  private final WorkBinding binding;
  private final WorkDataOutline outline;
  private final WorkRetainedContext context;
  private final WorkMapping mapping;

  public WorkDocumentFilling(
      WorkDocumentService documents,
      ProductPipelineRunStore runs,
      CompilationArtifacts artifacts,
      WorkTaskModel model,
      CatalogResolution catalog,
      Clock clock,
      WorkRecovery recovery,
      RetryPolicy retry) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.runs = Objects.requireNonNull(runs, "runs");
    Objects.requireNonNull(artifacts, "artifacts");
    this.model = Objects.requireNonNull(model, "model");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(clock, "clock");
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.retry = Objects.requireNonNull(retry, "retry");
    WorkTaskExecutor executor = new WorkTaskExecutor(documents, runs, clock);
    this.logical = new WorkLogicalFlow(documents, executor);
    this.binding = new WorkBinding(documents, runs, clock, catalog);
    this.outline = new WorkDataOutline(documents, executor);
    this.context = new WorkRetainedContext(documents, executor);
    this.mapping = new WorkMapping(documents, executor);
  }

  public FillingResult advance(String runId, String commandId) {
    requireText(runId, "runId");
    requireText(commandId, "commandId");
    String payloadHash = sha256(ADVANCE_PAYLOAD);
    String stored = documents.readFillingReceipt(runId, commandId, payloadHash);
    if (stored != null) {
      return readResult(stored);
    }
    Dispatch dispatch = dispatch(load(runId), commandId);
    if (dispatch != null) {
      return finish(runId, commandId, payloadHash, dispatch);
    }
    if (exhausted(load(runId))) {
      return persist(runId, commandId, payloadHash, halted(runId, "Recovery budget is spent."));
    }
    ChainWorkDocument document = documents.read(runId).document();
    WorkTaskPlanner.Plan plan = planner.plan(document);
    Target target = choose(runId, document, plan);
    if (target == null) {
      return persist(runId, commandId, payloadHash, terminal(runId, plan));
    }
    markDispatch(runId, commandId, target);
    if (target.cheap()) {
      stamp(runId, commandId, target.taskKey(), false);
      return persist(runId, commandId, payloadHash, afterTask(runId, target.taskId(), List.of("revalidated")));
    }
    return finish(runId, commandId, payloadHash, new Dispatch(target.kind(), target.recordId(), target.taskKey()));
  }

  public WorkCommit acceptInput(String runId, String questionId, String inputId, String text) {
    return documents.acceptInput(runId, questionId, inputId, text);
  }

  private FillingResult finish(String runId, String commandId, String payloadHash, Dispatch dispatch) {
    if (routed(load(runId), commandId)) {
      ensureStash(runId, commandId, dispatch);
      FillingResult.Action action =
          exhausted(load(runId)) ? FillingResult.Action.HALTED : FillingResult.Action.ADVANCED;
      return persist(runId, commandId, payloadHash, result(runId, action, dispatch.taskId(), List.of("routed"), 0L));
    }
    if (acceptedDispatch(runId, dispatch)) {
      return persist(runId, commandId, payloadHash, afterTask(runId, dispatch.taskId(), List.of("accepted")));
    }
    ChainWorkDocument current = documents.read(runId).document();
    WorkTaskPlanner.Task planned = planned(current, dispatch.taskKey());
    if (planned != null && cheap(current, planned)) {
      stamp(runId, commandId, dispatch.taskKey(), false);
      return persist(runId, commandId, payloadHash, afterTask(runId, dispatch.taskId(), List.of("revalidated")));
    }
    if (published(load(runId), dispatch)) {
      return persist(runId, commandId, payloadHash, settle(runId, commandId, dispatch, null));
    }
    Delivery delivery = new Delivery(runId, commandId, model);
    try {
      WorkCommit commit = invoke(runId, dispatch.kind(), dispatch.recordId(), delivery);
      if (delivery.stale) {
        return persist(
            runId,
            commandId,
            payloadHash,
            result(runId, FillingResult.Action.RETRY_CURRENT, dispatch.taskId(), List.of("stale-result"), 0L));
      }
      return persist(runId, commandId, payloadHash, settle(runId, commandId, dispatch, commit));
    } catch (StaleProposal stale) {
      return persist(
          runId,
          commandId,
          payloadHash,
          result(runId, FillingResult.Action.RETRY_CURRENT, dispatch.taskId(), List.of("stale-result"), 0L));
    } catch (WorkDocumentRejectedException rejected) {
      if ("STALE_SCOPE".equals(rejected.code())) {
        return persist(
            runId,
            commandId,
            payloadHash,
            result(runId, FillingResult.Action.RETRY_CURRENT, dispatch.taskId(), List.of(rejected.getMessage()), 0L));
      }
      if (haltCode(rejected.code())) {
        return persist(
            runId,
            commandId,
            payloadHash,
            result(runId, FillingResult.Action.HALTED, dispatch.taskId(), List.of(rejected.getMessage()), 0L));
      }
      if (routeCode(rejected.code())) {
        return persist(runId, commandId, payloadHash, routeRejection(runId, commandId, dispatch, rejected));
      }
      throw rejected;
    } catch (RuntimeException failure) {
      if (failure instanceof StaleProposal || delivery.stale) {
        return persist(
            runId,
            commandId,
            payloadHash,
            result(runId, FillingResult.Action.RETRY_CURRENT, dispatch.taskId(), List.of("stale-result"), 0L));
      }
      if (TransientFailures.isTransient(failure)) {
        return persist(runId, commandId, payloadHash, technical(runId, commandId, dispatch, failure));
      }
      throw failure;
    }
  }

  private FillingResult settle(String runId, String commandId, Dispatch dispatch, WorkCommit commit) {
    WorkCommit published = commit == null ? publishedCommit(load(runId), dispatch) : commit;
    if (published == null) {
      return result(runId, FillingResult.Action.ADVANCED, dispatch.taskId(), List.of("published"), 0L);
    }
    if (published.outcome() == WorkOutcome.INPUT_DEFECT) {
      return routeCommit(runId, commandId, dispatch, published);
    }
    if (published.outcome() == WorkOutcome.PREPARED) {
      stamp(runId, commandId, dispatch.taskKey(), true);
    }
    return afterTask(runId, dispatch.taskId(), List.of(published.outcome().name()));
  }

  private FillingResult routeRejection(
      String runId, String commandId, Dispatch dispatch, WorkDocumentRejectedException rejected) {
    ChainWorkDocument document = documents.read(runId).document();
    DefectSite site = site(document, dispatch, rejected.code(), rejected.getMessage(), "");
    return applyRoute(runId, commandId, dispatch, site);
  }

  private FillingResult routeCommit(String runId, String commandId, Dispatch dispatch, WorkCommit commit) {
    ChainWorkDocument document = commit.state().document();
    WorkFinding finding = newestFinding(document);
    String category = finding == null || finding.issueCategory().isBlank() ? "INPUT_DEFECT" : finding.issueCategory();
    String contradiction = finding == null ? "The capture was rejected." : finding.contradiction();
    String record = finding == null ? dispatch.recordId() : finding.recordRef();
    DefectSite site = site(document, dispatch, category, contradiction, record);
    return applyRoute(runId, commandId, dispatch, site);
  }

  private FillingResult applyRoute(String runId, String commandId, Dispatch dispatch, DefectSite site) {
    WorkRecovery.Result routed =
        recovery.route(
            runId,
            new WorkRecovery.Defect(
                "",
                site.origin(),
                site.category(),
                site.pointer(),
                site.contradiction(),
                List.of(evidenceId(documents.read(runId).document())),
                site.revealed(),
                site.revealedPointer()),
            "recover:" + commandId);
    if (routed.dispatched()) {
      stash(runId, commandId, routed.owner(), site.handlerKind(), site.handlerRecordId(), site);
      return result(
          runId,
          FillingResult.Action.ADVANCED,
          dispatch.taskId(),
          List.of(routed.owner().name(), site.category()),
          0L);
    }
    return halted(runId, routed.nextAction());
  }

  private FillingResult technical(String runId, String commandId, Dispatch dispatch, RuntimeException failure) {
    WorkRecovery.Result technical =
        recovery.technicalRetry(runId, failure, "technical:" + commandId, retry.maxTechnicalRetries());
    if (!technical.dispatched()) {
      return halted(runId, failure.getMessage());
    }
    return result(
        runId,
        FillingResult.Action.RETRY_CURRENT,
        dispatch.taskId(),
        List.of(failure.getClass().getSimpleName()),
        FillingRuntimeDecision.retryDelayMs(retry));
  }

  private void stamp(String runId, String commandId, String taskKey, boolean clearCorrective) {
    ChainWorkDocument document = documents.read(runId).document();
    WorkTaskPlanner.Plan plan = planner.plan(document);
    List<WorkTaskRecord> tasks = new ArrayList<>();
    for (WorkTaskRecord record : document.progress().tasks()) {
      if (clearCorrective && CORRECTIVE_KEY.equals(record.taskKey())) {
        continue;
      }
      tasks.add(record);
    }
    WorkTaskPlanner.Task completed = null;
    for (WorkTaskPlanner.Task task : plan.tasks()) {
      if (task.taskKey().equals(taskKey)) {
        completed = task;
      }
    }
    if (completed != null) {
      acceptCompleted(tasks, completed);
    }
    if (clearCorrective) {
      WorkTaskRecord stash = find(document.progress().tasks(), CORRECTIVE_KEY);
      if (stash != null) {
        String realKey = WorkTaskPlanner.taskKey(stash.kind(), stash.taskId());
        for (WorkTaskPlanner.Task task : plan.tasks()) {
          if (realKey.equals(task.taskKey())
              || (stash.kind() == WorkTaskKind.LOGICAL_DESIGN
                  && task.kind() == WorkTaskKind.LOGICAL_DESIGN)) {
            acceptCompleted(tasks, task);
          }
        }
      }
    }
    for (WorkTaskPlanner.Task task : plan.tasks()) {
      WorkTaskRecord stored = find(tasks, task.taskKey());
      if (stored == null || stored.acceptedInputFingerprint().isBlank()) {
        continue;
      }
      if (!stored.acceptedInputFingerprint().equals(task.requiredInputFingerprint())
          && stored.state() == WorkTaskState.ACCEPTED) {
        replace(
            tasks,
            new WorkTaskRecord(
                stored.taskKey(),
                stored.kind(),
                stored.taskId(),
                WorkTaskState.NEEDS_RECHECK,
                stored.stage(),
                stored.skillId(),
                stored.acceptedInputFingerprint(),
                stored.producedRecordIds()));
      }
    }
    List<WorkFinding> findings = new ArrayList<>(document.progress().findings());
    if (clearCorrective) {
      WorkTaskRecord stash = find(document.progress().tasks(), CORRECTIVE_KEY);
      if (stash != null) {
        String category = stash.skillId();
        String origin = stash.producedRecordIds().isEmpty() ? "" : stash.producedRecordIds().get(0);
        String repaired = stash.taskId();
        String pointer = stash.acceptedInputFingerprint();
        findings.removeIf(finding -> resolvedFinding(finding, category, origin, repaired, pointer));
      }
    }
    commitProgress(runId, "progress:" + commandId, document, tasks, findings, sha256(taskKey));
  }

  private Target choose(String runId, ChainWorkDocument document, WorkTaskPlanner.Plan plan) {
    WorkTaskRecord stash = find(document.progress().tasks(), CORRECTIVE_KEY);
    if (stash != null && repairing(load(runId))) {
      return new Target(stash.kind(), stash.taskId(), CORRECTIVE_KEY, false);
    }
    WorkTaskPlanner.Task selected = plan.selected();
    if (selected == null) {
      return null;
    }
    if (cheap(document, selected)) {
      return new Target(selected.kind(), selected.recordId(), selected.taskKey(), true);
    }
    return new Target(selected.kind(), selected.recordId(), selected.taskKey(), false);
  }

  private boolean acceptedDispatch(String runId, Dispatch dispatch) {
    if (CORRECTIVE_KEY.equals(dispatch.taskKey())) {
      return false;
    }
    ChainWorkDocument document = documents.read(runId).document();
    WorkTaskPlanner.Task task = planned(document, dispatch.taskKey());
    WorkTaskRecord stored = find(document.progress().tasks(), dispatch.taskKey());
    return task != null
        && stored != null
        && stored.state() == WorkTaskState.ACCEPTED
        && task.requiredInputFingerprint().equals(stored.acceptedInputFingerprint());
  }

  private static WorkTaskPlanner.Task planned(ChainWorkDocument document, String taskKey) {
    if (taskKey == null || taskKey.isBlank() || CORRECTIVE_KEY.equals(taskKey)) {
      return null;
    }
    for (WorkTaskPlanner.Task task : new WorkTaskPlanner().plan(document).tasks()) {
      if (taskKey.equals(task.taskKey())) {
        return task;
      }
    }
    return null;
  }

  private boolean cheap(ChainWorkDocument document, WorkTaskPlanner.Task task) {
    if (task.kind() == WorkTaskKind.LOGICAL_DESIGN) {
      return false;
    }
    if (task.state() != WorkTaskState.NEEDS_RECHECK && task.state() != WorkTaskState.PENDING) {
      return false;
    }
    WorkTaskRecord stored = find(document.progress().tasks(), task.taskKey());
    if (stored == null || stored.acceptedInputFingerprint().isBlank()) {
      return false;
    }
    if (task.kind() == WorkTaskKind.DEFINE_TRANSFERS) {
      return cheapOutline(document, task);
    }
    if (task.kind() == WorkTaskKind.DESCRIBE_CONTEXT) {
      return cheapContext(document, task);
    }
    if (task.kind() == WorkTaskKind.MAP_TRANSFER) {
      return cheapMapping(document, task);
    }
    return false;
  }

  private boolean cheapOutline(ChainWorkDocument document, WorkTaskPlanner.Task task) {
    LogicalStep step = step(document, task.recordId());
    if (step == null || (step.data().transfers().isEmpty() && step.data().outline().coverage().isEmpty())) {
      return false;
    }
    if (!coverageApplies(document, step)) {
      return false;
    }
    if (!bindingHashesMatch(step)) {
      return false;
    }
    for (DataTransfer transfer : step.data().transfers()) {
      if (!portHashMatches(document, transfer.targetPort())) {
        return false;
      }
      for (PortRef source : transfer.sourcePorts()) {
        if (!portHashMatches(document, source)) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean cheapContext(ChainWorkDocument document, WorkTaskPlanner.Task task) {
    LogicalStep producer = step(document, task.recordId());
    if (producer == null || producer.binding() == null) {
      return false;
    }
    boolean sawValue = false;
    for (LogicalStep step : document.flow().steps()) {
      for (RetainedValue value : step.data().retainedValues()) {
        if (!producer.id().equals(value.producerStepId())) {
          continue;
        }
        sawValue = true;
        if (value.resolution() != RetainedResolution.RESOLVED || value.source() == null) {
          return false;
        }
        if (!pathInCurrentSchema(producer, value)) {
          return false;
        }
      }
    }
    return sawValue;
  }

  private boolean cheapMapping(ChainWorkDocument document, WorkTaskPlanner.Task task) {
    DataTransfer transfer = findTransfer(document, task.recordId());
    if (transfer == null || transfer.rules().isEmpty()) {
      return false;
    }
    if (!portHashMatches(document, transfer.targetPort())) {
      return false;
    }
    for (PortRef source : transfer.sourcePorts()) {
      if (!portHashMatches(document, source)) {
        return false;
      }
    }
    for (MappingRule rule : transfer.rules()) {
      if (rule.target() == null || !fieldStillApplies(document, rule.target())) {
        return false;
      }
      for (FieldReference source : rule.sources()) {
        if (!fieldStillApplies(document, source)) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean fieldStillApplies(ChainWorkDocument document, FieldReference field) {
    if (field.kind() == FieldReferenceKind.RETAINED) {
      RetainedValue value = findRetained(document, field.retainedValueId());
      if (value == null || value.resolution() != RetainedResolution.RESOLVED || value.source() == null) {
        return false;
      }
      LogicalStep producer = step(document, value.producerStepId());
      return producer != null && pathInCurrentSchema(producer, value);
    }
    if (field.kind() != FieldReferenceKind.STEP_PORT || field.port() == null) {
      return false;
    }
    LogicalStep owner = step(document, field.stepId());
    return owner != null && pathInSchema(owner, field.port().schemaName(), field.fieldPath());
  }

  private boolean coverageApplies(ChainWorkDocument document, LogicalStep step) {
    List<String> requirements = new ArrayList<>();
    for (WorkRequirement requirement : document.requirements()) {
      requirements.add(requirement.id());
    }
    List<String> passages = new ArrayList<>();
    for (WorkSource source : document.sources()) {
      for (SourcePassage passage : source.passages()) {
        passages.add(passage.id());
      }
    }
    for (CoverageEntry entry : step.data().outline().coverage()) {
      if (!requirements.contains(entry.requirementId()) || !passages.contains(entry.passageId())) {
        return false;
      }
    }
    for (DataTransfer transfer : step.data().transfers()) {
      for (String requirementId : transfer.requirementIds()) {
        if (!requirements.contains(requirementId)) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean bindingHashesMatch(LogicalStep step) {
    if (step.binding() == null || step.kind() == StepKind.LOCAL) {
      return true;
    }
    ContractMaterial material = catalog.loadContract(step.binding());
    if (!(material instanceof ContractMaterial.Ready ready)) {
      return false;
    }
    for (ResolvedWorkBinding.PortContentHash stored : step.binding().portContentHashes()) {
      if (!hashMatches(ready, stored.port(), stored.contentHash())) {
        return false;
      }
    }
    return true;
  }

  private boolean portHashMatches(ChainWorkDocument document, PortRef port) {
    if (port == null) {
      return true;
    }
    LogicalStep owner = step(document, port.stepId());
    if (owner == null) {
      return false;
    }
    if (owner.binding() == null || owner.kind() == StepKind.LOCAL) {
      return true;
    }
    String storedHash = "";
    for (ResolvedWorkBinding.PortContentHash hash : owner.binding().portContentHashes()) {
      if (port.portName().equals(hash.port())) {
        storedHash = hash.contentHash();
      }
    }
    if (storedHash.isBlank()) {
      return false;
    }
    ContractMaterial material = catalog.loadContract(owner.binding());
    if (!(material instanceof ContractMaterial.Ready ready)) {
      return false;
    }
    return hashMatches(ready, port.portName(), storedHash);
  }

  private static boolean hashMatches(ContractMaterial.Ready ready, String port, String contentHash) {
    for (PortSchemaMaterial schema : ready.ports()) {
      if (port.equals(schema.port())
          && contentHash.equals(schema.contentHash())
          && schema.schema() != null
          && !schema.schema().isNull()) {
        return true;
      }
    }
    return false;
  }

  private boolean pathInCurrentSchema(LogicalStep producer, RetainedValue value) {
    if (producer.binding() == null) {
      return false;
    }
    ContractMaterial material = catalog.loadContract(producer.binding());
    if (!(material instanceof ContractMaterial.Ready ready)) {
      return false;
    }
    String port = value.source().port() == null ? "" : value.source().port().schemaName();
    return pathInReady(producer, ready, port, value.source().fieldPath());
  }

  private boolean pathInSchema(LogicalStep producer, String port, String path) {
    if (producer.binding() == null) {
      return false;
    }
    ContractMaterial material = catalog.loadContract(producer.binding());
    if (!(material instanceof ContractMaterial.Ready ready)) {
      return false;
    }
    return pathInReady(producer, ready, port, path);
  }

  private static boolean pathInReady(LogicalStep producer, ContractMaterial.Ready ready, String port, String path) {
    for (PortSchemaMaterial schema : ready.ports()) {
      if (!port.equals(schema.port()) || schema.schema() == null) {
        continue;
      }
      return new SchemaFragment(
              producer.id() + ":" + port,
              producer.id(),
              port,
              schema.contentHash(),
              schema.contractReference(),
              schema.schema().toString())
          .containsPath(path);
    }
    return false;
  }

  private WorkCommit invoke(String runId, WorkTaskKind kind, String recordId, WorkTaskModel delivery) {
    rejectIncompatible(documents.read(runId).document(), kind, recordId);
    WorkTaskMaterials materials = materials(documents.read(runId).document());
    List<ContractMaterial> contracts = contracts(documents.read(runId).document());
    return switch (kind) {
      case LOGICAL_DESIGN -> design(runId, recordId, materials, delivery);
      case SELECT_OPERATION -> binding.select(runId, recordId, materials, delivery);
      case DEFINE_TRANSFERS -> outline.propose(runId, recordId, materials, contracts, delivery, recordId);
      case DESCRIBE_CONTEXT -> context.describe(runId, recordId, materials, delivery);
      case MAP_TRANSFER -> mapping.interpret(runId, recordId, materials, delivery);
      case REPAIR_RULE -> mapping.repair(runId, recordId, materials, delivery);
      default -> throw new IllegalArgumentException("Task kind " + kind + " has no filling handler.");
    };
  }

  private WorkCommit design(String runId, String recordId, WorkTaskMaterials materials, WorkTaskModel delivery) {
    ChainWorkDocument document = documents.read(runId).document();
    if (document.flow().steps().isEmpty()) {
      return logical.design(runId, materials, delivery);
    }
    String requirementId = recordId;
    if (document.documentId().equals(recordId) && !document.requirements().isEmpty()) {
      requirementId = document.requirements().get(0).id();
    }
    return logical.repair(runId, requirementId, materials, delivery);
  }

  private void markDispatch(String runId, String commandId, Target target) {
    ProductPipelineRunDocument current = load(runId);
    String marker = "dispatch:" + commandId;
    String hash = sha256(target.kind().name() + "|" + target.recordId());
    if (current.appliedCommand(marker, hash).isPresent()) {
      return;
    }
    documents.recordFillingReceipt(
        runId,
        marker,
        hash,
        DISPATCH_PREFIX + target.kind().name() + "|" + target.recordId() + "|" + target.taskKey(),
        "",
        null);
  }

  private void stash(
      String runId,
      String commandId,
      WorkStage owner,
      WorkTaskKind kind,
      String recordId,
      DefectSite site) {
    ChainWorkDocument document = documents.read(runId).document();
    List<WorkTaskRecord> tasks = new ArrayList<>();
    for (WorkTaskRecord record : document.progress().tasks()) {
      if (!CORRECTIVE_KEY.equals(record.taskKey())) {
        tasks.add(record);
      }
    }
    tasks.add(
        new WorkTaskRecord(
            CORRECTIVE_KEY,
            kind,
            recordId,
            WorkTaskState.PENDING,
            owner,
            site.category(),
            site.pointer(),
            List.of(site.origin())));
    commitProgress(runId, "stash:" + commandId, document, tasks, document.progress().findings(), sha256(recordId));
  }

  private void ensureStash(String runId, String commandId, Dispatch dispatch) {
    if (find(documents.read(runId).document().progress().tasks(), CORRECTIVE_KEY) != null) {
      return;
    }
    if (!repairing(load(runId))) {
      return;
    }
    ChainWorkDocument document = documents.read(runId).document();
    DefectSite site = site(document, dispatch, "INPUT_DEFECT", "The capture was rejected.", dispatch.recordId());
    stash(runId, commandId, site.owner(), site.handlerKind(), site.handlerRecordId(), site);
  }

  private void commitProgress(
      String runId,
      String commandId,
      ChainWorkDocument document,
      List<WorkTaskRecord> tasks,
      List<WorkFinding> findings,
      String payloadHash) {
    WorkProgress progress = document.progress();
    ChainWorkDocument next =
        new ChainWorkDocument(
            document.schemaVersion(),
            document.documentId(),
            document.sources(),
            document.requirements(),
            document.flow(),
            new WorkProgress(
                tasks,
                findings,
                progress.questions(),
                progress.approvalReference(),
                progress.derivedResultReferences(),
                progress.recheckStages()));
    documents.commitRecoveredDocument(
        runId, next, commandId, payloadHash, "task-progress", documentStage(load(runId)), null);
  }

  private FillingResult afterTask(String runId, String taskId, List<String> reasons) {
    WorkTaskPlanner.Plan plan = planner.plan(documents.read(runId).document());
    if (plan.selected() != null) {
      return result(runId, FillingResult.Action.ADVANCED, taskId, reasons, 0L);
    }
    FillingResult terminal = terminal(runId, plan);
    if (terminal.action() == FillingResult.Action.ADVANCED) {
      return result(runId, FillingResult.Action.ADVANCED, taskId, reasons, 0L);
    }
    List<String> merged = new ArrayList<>(reasons);
    merged.addAll(terminal.reasons());
    return result(runId, terminal.action(), taskId, merged, terminal.retryDelayMs());
  }

  private FillingResult terminal(String runId, WorkTaskPlanner.Plan plan) {
    WorkTaskPlanner.Readiness readiness = plan.readiness();
    return switch (readiness.status()) {
      case READY_FOR_PRESENTATION ->
          result(runId, FillingResult.Action.READY_FOR_PRESENTATION, "", List.of("ready"), 0L);
      case WAITING_FOR_INPUT ->
          result(runId, FillingResult.Action.WAITING_FOR_INPUT, "", List.of("waiting-for-input"), 0L);
      case HALTED -> halted(runId, "A structural defect or unmet dependency remains.");
      case WORK_REMAINING -> result(runId, FillingResult.Action.ADVANCED, "", List.of("work-remaining"), 0L);
    };
  }

  private FillingResult halted(String runId, String reason) {
    List<String> reasons = new ArrayList<>();
    if (reason != null && !reason.isBlank()) {
      reasons.add(reason);
    }
    ChainWorkDocument document = documents.read(runId).document();
    for (WorkQuestion question : document.progress().questions()) {
      if ("NEXT_ACTION".equals(question.choice()) && question.resolution() == QuestionResolution.OPEN) {
        reasons.add(question.question());
      }
    }
    return result(runId, FillingResult.Action.HALTED, "", reasons, 0L);
  }

  private FillingResult persist(String runId, String commandId, String payloadHash, FillingResult result) {
    RunStatus status =
        result.action() == FillingResult.Action.WAITING_FOR_INPUT
            ? RunStatus.WAITING_FOR_INPUT
            : RunStatus.RUNNING;
    documents.recordFillingReceipt(
        runId, commandId, payloadHash, "filling-receipt", writeResult(result), status);
    return result;
  }

  private FillingResult result(
      String runId, FillingResult.Action action, String taskId, List<String> reasons, long delay) {
    ChainWorkDocument document = documents.read(runId).document();
    ProductPipelineRunDocument run = load(runId);
    String reference = "";
    if (run.run().workDocumentRef() != null) {
      reference = run.run().workDocumentRef().artifactId();
    }
    return new FillingResult(
        action,
        taskId,
        WorkDocumentState.of(document).revision(),
        reference,
        questionIds(document),
        reasons,
        delay);
  }

  private static List<String> questionIds(ChainWorkDocument document) {
    List<String> ids = new ArrayList<>();
    for (WorkQuestion question : document.progress().questions()) {
      if (question.resolution() != QuestionResolution.OPEN || "NEXT_ACTION".equals(question.choice())) {
        continue;
      }
      ids.add(question.id());
    }
    ids.sort(String::compareTo);
    return ids;
  }

  private WorkTaskMaterials materials(ChainWorkDocument document) {
    Map<String, String> evidence = new LinkedHashMap<>();
    for (WorkSource source : document.sources()) {
      if (!source.content().isBlank()) {
        evidence.put(source.id(), source.content());
      }
      for (SourcePassage passage : source.passages()) {
        evidence.put(passage.id(), passage.text());
      }
    }
    List<SchemaFragment> schemas = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      if (step.binding() == null) {
        continue;
      }
      ContractMaterial material = catalog.loadContract(step.binding());
      if (!(material instanceof ContractMaterial.Ready ready)) {
        continue;
      }
      for (PortSchemaMaterial port : ready.ports()) {
        schemas.add(
            new SchemaFragment(
                step.id() + ":" + port.port(),
                step.id(),
                port.port(),
                port.contentHash(),
                port.contractReference(),
                port.schema() == null ? "" : port.schema().toString()));
      }
    }
    return new WorkTaskMaterials(schemas, List.of("runtime-catalog-only"), evidence);
  }

  private void rejectIncompatible(ChainWorkDocument document, WorkTaskKind kind, String recordId) {
    if (kind != WorkTaskKind.DEFINE_TRANSFERS && kind != WorkTaskKind.MAP_TRANSFER) {
      return;
    }
    for (String stepId : contractSteps(document, kind, recordId)) {
      LogicalStep step = step(document, stepId);
      if (step == null || step.binding() == null) {
        continue;
      }
      ContractMaterial material = catalog.loadContract(step.binding());
      if (material instanceof ContractMaterial.Incompatible incompatible) {
        throw new WorkDocumentRejectedException(
            "INCOMPATIBLE_CONTRACT",
            incompatible.reason() + " Step " + step.id() + ".");
      }
    }
  }

  private String incompatibleStep(ChainWorkDocument document, Dispatch dispatch) {
    for (String stepId : contractSteps(document, dispatch.kind(), dispatch.recordId())) {
      LogicalStep step = step(document, stepId);
      if (step == null || step.binding() == null) {
        continue;
      }
      if (catalog.loadContract(step.binding()) instanceof ContractMaterial.Incompatible) {
        return step.id();
      }
    }
    return "";
  }

  private static List<String> contractSteps(ChainWorkDocument document, WorkTaskKind kind, String recordId) {
    List<String> ids = new ArrayList<>();
    if (kind == WorkTaskKind.DEFINE_TRANSFERS) {
      ids.add(recordId);
      for (LogicalConnection connection : document.flow().connections()) {
        if (recordId.equals(connection.targetStepId())) {
          ids.add(connection.sourceStepId());
        }
      }
      return ids;
    }
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        if (!recordId.equals(transfer.id())) {
          continue;
        }
        if (transfer.targetPort() != null) {
          ids.add(transfer.targetPort().stepId());
        }
        for (PortRef source : transfer.sourcePorts()) {
          ids.add(source.stepId());
        }
      }
    }
    return ids;
  }

  private List<ContractMaterial> contracts(ChainWorkDocument document) {
    List<ContractMaterial> loaded = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      if (step.binding() != null) {
        loaded.add(catalog.loadContract(step.binding()));
      }
    }
    return loaded;
  }

  private DefectSite site(
      ChainWorkDocument document, Dispatch dispatch, String category, String contradiction, String record) {
    String origin = record == null || record.isBlank() ? dispatch.recordId() : record;
    String pointer = "";
    String revealed = "";
    String revealedPointer = "";
    WorkTaskKind handler = dispatch.kind();
    String handlerRecord = dispatch.recordId();
    WorkFinding open = openCause(document, category);
    if (open != null && !open.canonicalFieldPointer().isBlank()) {
      origin = open.recordRef();
      pointer = open.canonicalFieldPointer();
    }
    if ("WRONG_OPERATION".equals(category)
        && isRequirement(document, record)
        && dispatch.kind() == WorkTaskKind.SELECT_OPERATION) {
      origin = dispatch.recordId();
      pointer = "binding";
      revealed = record;
      revealedPointer = "text";
      handler = WorkTaskKind.LOGICAL_DESIGN;
      handlerRecord = record;
    } else if ("WRONG_OPERATION".equals(category) && !targetStep(document, origin).isBlank()) {
      pointer = "behavior";
      revealed = targetStep(document, origin);
      revealedPointer = "binding";
      handler = WorkTaskKind.SELECT_OPERATION;
      handlerRecord = revealed;
    } else if ("WRONG_OPERATION".equals(category)) {
      pointer = "binding";
      handler = WorkTaskKind.SELECT_OPERATION;
      handlerRecord = step(document, origin) == null ? dispatch.recordId() : origin;
    } else if ("INCOMPATIBLE_CONTRACT".equals(category)) {
      String broken = incompatibleStep(document, dispatch);
      origin = dispatch.recordId();
      if (step(document, origin) != null) {
        pointer = "data";
      }
      revealed = broken;
      revealedPointer = "binding";
      handler = WorkTaskKind.SELECT_OPERATION;
      handlerRecord = broken.isBlank() ? dispatch.recordId() : broken;
    } else if (ServerOwnedSubjects.OUTLINE_POINTER.equals(pointer) || "MISSING_RETAINED".equals(category)) {
      pointer = ServerOwnedSubjects.OUTLINE_POINTER;
      handler = WorkTaskKind.DEFINE_TRANSFERS;
      handlerRecord = stepIdOf(document, origin, dispatch.recordId());
      origin = handlerRecord;
    } else if (dispatch.kind() == WorkTaskKind.DEFINE_TRANSFERS) {
      pointer = ServerOwnedSubjects.OUTLINE_POINTER;
      origin = step(document, dispatch.recordId()) == null ? origin : dispatch.recordId();
      handler = WorkTaskKind.DEFINE_TRANSFERS;
      handlerRecord = origin;
    } else if (dispatch.kind() == WorkTaskKind.LOGICAL_DESIGN && document.flow().steps().isEmpty()) {
      origin = document.documentId();
      pointer = ServerOwnedSubjects.FLOW_POINTER;
      handler = WorkTaskKind.LOGICAL_DESIGN;
      handlerRecord = document.documentId();
    } else if (dispatch.kind() == WorkTaskKind.DESCRIBE_CONTEXT) {
      handler = WorkTaskKind.DESCRIBE_CONTEXT;
      handlerRecord = dispatch.recordId();
      origin = origin.isBlank() ? dispatch.recordId() : origin;
    }
    WorkStage owner = stageOf(handler);
    return new DefectSite(
        origin, category, pointer, contradiction, revealed, revealedPointer, handler, handlerRecord, owner);
  }

  private static WorkFinding openCause(ChainWorkDocument document, String category) {
    WorkFinding found = null;
    for (WorkFinding finding : document.progress().findings()) {
      if (category.equals(finding.issueCategory()) && !finding.canonicalFieldPointer().isBlank()) {
        found = finding;
      }
    }
    return found;
  }

  private static String targetStep(ChainWorkDocument document, String recordId) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        if (recordId.equals(transfer.id()) && transfer.targetPort() != null) {
          return transfer.targetPort().stepId();
        }
        for (MappingRule rule : transfer.rules()) {
          if (recordId.equals(rule.id()) && transfer.targetPort() != null) {
            return transfer.targetPort().stepId();
          }
        }
      }
    }
    return "";
  }

  private static boolean isRequirement(ChainWorkDocument document, String recordId) {
    if (recordId == null || recordId.isBlank()) {
      return false;
    }
    for (WorkRequirement requirement : document.requirements()) {
      if (recordId.equals(requirement.id())) {
        return true;
      }
    }
    return false;
  }

  private static String requirementId(ChainWorkDocument document, String stepId) {
    LogicalStep step = step(document, stepId);
    if (step == null || step.requirementIds().isEmpty()) {
      return "";
    }
    return step.requirementIds().get(0);
  }

  private static DataTransfer findTransfer(ChainWorkDocument document, String transferId) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        if (transferId.equals(transfer.id())) {
          return transfer;
        }
      }
    }
    return null;
  }

  private static RetainedValue findRetained(ChainWorkDocument document, String retainedId) {
    for (LogicalStep step : document.flow().steps()) {
      for (RetainedValue value : step.data().retainedValues()) {
        if (retainedId.equals(value.id())) {
          return value;
        }
      }
    }
    return null;
  }

  private static String stepIdOf(ChainWorkDocument document, String origin, String fallback) {
    if (step(document, origin) != null) {
      return origin;
    }
    if (step(document, fallback) != null) {
      return fallback;
    }
    String target = targetStep(document, origin);
    return target.isBlank() ? fallback : target;
  }

  private static WorkStage stageOf(WorkTaskKind kind) {
    return switch (kind) {
      case LOGICAL_DESIGN -> WorkStage.LOGICAL_FLOW;
      case SELECT_OPERATION -> WorkStage.SERVICES;
      default -> WorkStage.DATA_BEHAVIOR;
    };
  }

  private static boolean routeCode(String code) {
    return switch (code) {
      case "MALFORMED_CAPTURE",
          "MALFORMED_REFERENCE",
          "CONTRADICTORY_OUTCOME",
          "OUTSIDE_SCOPE",
          "SERVER_OWNED_FIELD",
          "SYNCHRONOUS_RESULT",
          "UNEVIDENCED_MAPPING",
          "MISSING_SCHEMA",
          "UNKNOWN_EVIDENCE",
          "OUTCOME_MISMATCH",
          "MISSING_COVERAGE",
          "WRONG_OPERATION",
          "INVALID_CONSTANT",
          "FABRICATED_PREFIX",
          "INCOMPATIBLE_CONTRACT",
          "INPUT_DEFECT" -> true;
      default -> false;
    };
  }

  private static boolean haltCode(String code) {
    return "UNAVAILABLE_CONTRACT".equals(code) || "SCHEMA_READ_FAILED".equals(code);
  }

  private Dispatch dispatch(ProductPipelineRunDocument run, String commandId) {
    String marker = "dispatch:" + commandId;
    for (RunTransition transition : run.transitions()) {
      if (!marker.equals(transition.commandId()) || transition.reason() == null) {
        continue;
      }
      String reason = transition.reason();
      if (!reason.startsWith(DISPATCH_PREFIX)) {
        continue;
      }
      String body = reason.substring(DISPATCH_PREFIX.length());
      int bar = body.indexOf('|');
      int next = bar < 0 ? -1 : body.indexOf('|', bar + 1);
      if (bar < 0 || next < 0) {
        continue;
      }
      WorkTaskKind kind = WorkTaskKind.valueOf(body.substring(0, bar));
      String recordId = body.substring(bar + 1, next);
      String taskKey = body.substring(next + 1);
      return new Dispatch(kind, recordId, taskKey);
    }
    return null;
  }

  private boolean published(ProductPipelineRunDocument run, Dispatch dispatch) {
    String prefix = invocationPrefix(run, dispatch);
    for (RunTransition transition : run.transitions()) {
      String command = transition.commandId();
      if (command == null || !command.startsWith(prefix) || command.endsWith("#start")) {
        continue;
      }
      return true;
    }
    return false;
  }

  private WorkCommit publishedCommit(ProductPipelineRunDocument run, Dispatch dispatch) {
    String prefix = invocationPrefix(run, dispatch);
    RunTransition found = null;
    for (RunTransition transition : run.transitions()) {
      String command = transition.commandId();
      if (command != null && command.startsWith(prefix) && !command.endsWith("#start")) {
        found = transition;
      }
    }
    if (found == null) {
      return null;
    }
    return documents.committedResult(run, found);
  }

  private String invocationPrefix(ProductPipelineRunDocument run, Dispatch dispatch) {
    return WorkTaskPlanner.taskId(dispatch.kind(), dispatch.recordId())
        + ":"
        + documents.read(run.run().runId()).revision();
  }

  private boolean routed(ProductPipelineRunDocument run, String commandId) {
    String route = "recover:" + commandId;
    for (RunTransition transition : run.transitions()) {
      if (route.equals(transition.commandId())) {
        return true;
      }
    }
    return false;
  }

  private boolean exhausted(ProductPipelineRunDocument run) {
    String reason = latestRecovery(run);
    return reason != null && reason.startsWith(EXHAUSTED_PREFIX);
  }

  private boolean repairing(ProductPipelineRunDocument run) {
    String reason = latestRecovery(run);
    return reason != null && reason.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX);
  }

  private static String latestRecovery(ProductPipelineRunDocument run) {
    String reason = null;
    for (RunTransition transition : run.transitions()) {
      String candidate = transition.reason();
      if (candidate == null) {
        continue;
      }
      if (candidate.startsWith(ProductPipelineStageExecutor.PRODUCER_REPAIR_REASON_PREFIX)
          || candidate.startsWith(EXHAUSTED_PREFIX)) {
        reason = candidate;
      }
    }
    return reason;
  }

  private static void acceptCompleted(List<WorkTaskRecord> tasks, WorkTaskPlanner.Task completed) {
    WorkTaskRecord stored = find(tasks, completed.taskKey());
    WorkTaskRecord stamped =
        stored == null
            ? new WorkTaskRecord(
                completed.taskKey(),
                completed.kind(),
                completed.taskId(),
                WorkTaskState.ACCEPTED,
                completed.stage(),
                completed.skillId(),
                completed.requiredInputFingerprint(),
                List.of(completed.recordId()))
            : accepted(stored, completed.requiredInputFingerprint());
    replace(tasks, stamped);
  }

  private static boolean resolvedFinding(
      WorkFinding finding, String category, String origin, String repaired, String pointer) {
    if (category == null || category.isBlank() || !category.equals(finding.issueCategory())) {
      return false;
    }
    boolean sameRecord = finding.recordRef().equals(origin) || finding.recordRef().equals(repaired);
    if (!sameRecord) {
      return false;
    }
    if (pointer == null || pointer.isBlank() || finding.canonicalFieldPointer().isBlank()) {
      return finding.recordRef().equals(origin);
    }
    return pointer.equals(finding.canonicalFieldPointer());
  }

  private static String evidenceId(ChainWorkDocument document) {
    if (document.sources().isEmpty()) {
      return document.documentId();
    }
    return document.sources().get(0).id();
  }

  private static WorkFinding newestFinding(ChainWorkDocument document) {
    List<WorkFinding> findings = document.progress().findings();
    if (findings.isEmpty()) {
      return null;
    }
    return findings.get(findings.size() - 1);
  }

  private static LogicalStep step(ChainWorkDocument document, String stepId) {
    for (LogicalStep step : document.flow().steps()) {
      if (step.id().equals(stepId)) {
        return step;
      }
    }
    return null;
  }

  private static WorkTaskRecord find(List<WorkTaskRecord> tasks, String taskKey) {
    for (WorkTaskRecord task : tasks) {
      if (taskKey.equals(task.taskKey())) {
        return task;
      }
    }
    return null;
  }

  private static void replace(List<WorkTaskRecord> tasks, WorkTaskRecord replacement) {
    for (int index = 0; index < tasks.size(); index++) {
      if (replacement.taskKey().equals(tasks.get(index).taskKey())) {
        tasks.set(index, replacement);
        return;
      }
    }
    tasks.add(replacement);
  }

  private static WorkTaskRecord accepted(WorkTaskRecord stored, String fingerprint) {
    return new WorkTaskRecord(
        stored.taskKey(),
        stored.kind(),
        stored.taskId(),
        WorkTaskState.ACCEPTED,
        stored.stage(),
        stored.skillId(),
        fingerprint,
        stored.producedRecordIds());
  }

  private static String documentStage(ProductPipelineRunDocument run) {
    return run.run().currentStageId();
  }

  private ProductPipelineRunDocument load(String runId) {
    return runs.load(runId).orElseThrow(() -> new IllegalArgumentException("Run was not found: " + runId));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private FillingResult readResult(String receipt) {
    try {
      return JSON.readValue(receipt, FillingResult.class);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot read the filling receipt.", failure);
    }
  }

  private String writeResult(FillingResult result) {
    try {
      return JSON.writeValueAsString(result);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot write the filling receipt.", failure);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private record Dispatch(WorkTaskKind kind, String recordId, String taskKey) {
    String taskId() {
      return WorkTaskPlanner.taskId(kind, recordId);
    }
  }

  private record Target(WorkTaskKind kind, String recordId, String taskKey, boolean cheap) {
    String taskId() {
      return WorkTaskPlanner.taskId(kind, recordId);
    }
  }

  private record DefectSite(
      String origin,
      String category,
      String pointer,
      String contradiction,
      String revealed,
      String revealedPointer,
      WorkTaskKind handlerKind,
      String handlerRecordId,
      WorkStage owner) {}

  /** The provider answered, then the document changed. The handler must not publish that output. */
  private static final class StaleProposal extends RuntimeException {}

  private final class Delivery implements WorkTaskModel {
    private final String runId;
    private final String commandId;
    private final WorkTaskModel delegate;
    private int calls;
    private boolean stale;

    private Delivery(String runId, String commandId, WorkTaskModel delegate) {
      this.runId = runId;
      this.commandId = commandId;
      this.delegate = delegate;
    }

    @Override
    public String complete(WorkTaskRequest request) {
      if (calls > 0) {
        throw new IllegalStateException("An advance already called the model.");
      }
      calls++;
      String before = documents.read(runId).revision();
      String reservation = commandId + ":" + reservationCount(runId, commandId);
      runs.reserveProviderDelivery(runId, reservation);
      try {
        String output = delegate.complete(request);
        runs.recordProviderDelivery(runId, reservation, ProviderDeliveryOutcome.COMPLETED);
        if (!before.equals(documents.read(runId).revision())) {
          stale = true;
          throw new StaleProposal();
        }
        return output;
      } catch (StaleProposal proposal) {
        throw proposal;
      } catch (RuntimeException failure) {
        if (TransientFailures.isTransient(failure)) {
          runs.recordProviderDelivery(runId, reservation, ProviderDeliveryOutcome.UNCERTAIN);
        }
        throw failure;
      }
    }
  }

  private int reservationCount(String runId, String commandId) {
    int count = 1;
    String prefix = commandId + ":";
    for (String reservation : runs.providerDeliveryReservations(load(runId))) {
      if (reservation.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }
}
