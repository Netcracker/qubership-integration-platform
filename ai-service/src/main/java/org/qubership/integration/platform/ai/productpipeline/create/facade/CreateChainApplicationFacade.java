package org.qubership.integration.platform.ai.productpipeline.create.facade;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.ImplementationPlanChatView;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapWait;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBinding;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.ResponseLocaleResolver;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.StaleApprovalException;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Transport-neutral application facade for {@code create-chain@2}.
 *
 * <p>The {@link CreateChainOrchestrator} remains the lifecycle authority. Browser and A2A adapters call
 * this facade without sharing transport DTOs.
 */
@ApplicationScoped
public class CreateChainApplicationFacade {

  private static final Pattern INTERNAL_TOKEN =
      Pattern.compile(
          "\\b(?:READY_FOR_[A-Z0-9_]+|WAITING_FOR_[A-Z0-9_]+|CHAIN_MATERIALIZED|PLAN_APPROVED|"
              + "NEEDS_INPUT|CONTRACT_FAILURE|RETRYABLE_TECHNICAL_FAILURE|"
              + "MISSING_MANDATORY_INPUT|RUNNING|FAILED)\\b");

  private static final String DEFAULT_CLARIFY_REASON = "Additional input is required.";

  private final CreateRunSelectionService selectionService;
  private final CreateRunBindingStore bindingStore;
  private final CreateChainOrchestrator runtime;
  private final ProductPipelineRunStore runStore;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final ProductPipelineArtifactStore artifactStore;
  private final RequirementDraftStore draftStore;

  @Inject
  public CreateChainApplicationFacade(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      CreateChainOrchestrator runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ProductPipelineArtifactStore artifactStore,
      RequirementDraftStore draftStore) {
    this.selectionService = Objects.requireNonNull(selectionService, "selectionService");
    this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.draftStore = Objects.requireNonNull(draftStore, "draftStore");
  }

  /** Test helper that wires a no-op artifact store when evidence resolution is unused. */
  public CreateChainApplicationFacade(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      CreateChainOrchestrator runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog) {
    this(selectionService, bindingStore, runtime, runStore, profileCatalog, new RequirementDraftStore());
  }

  /** Test helper with an explicit artifact store and a fresh in-memory draft store. */
  public CreateChainApplicationFacade(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      CreateChainOrchestrator runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ProductPipelineArtifactStore artifactStore) {
    this(
        selectionService,
        bindingStore,
        runtime,
        runStore,
        profileCatalog,
        artifactStore,
        new RequirementDraftStore());
  }

  /** Test helper with an explicit draft store for clarify-prompt assertions. */
  public CreateChainApplicationFacade(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      CreateChainOrchestrator runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      RequirementDraftStore draftStore) {
    this(
        selectionService,
        bindingStore,
        runtime,
        runStore,
        profileCatalog,
        new ProductPipelineArtifactStore(
            new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts(
                new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                java.time.Clock.systemUTC())),
        draftStore);
  }

  /** Starts or resumes create-chain for {@code taskId} (== conversationId). */
  public Multi<CreateChainEvent> start(StartCreateChainCommand command) {
    Objects.requireNonNull(command, "command");
    String taskId = command.taskId();
    selectionService.selectOrCreate(taskId, command.requirementText());
    CreateRunBinding binding = requireBinding(taskId);

    Optional<ProductPipelineRunDocument> existing = runStore.loadByConversation(taskId);
    if (existing.isEmpty()) {
      // startOrResume advances discovery before userText is attached, which emits a silent
      // NEEDS_INPUT wait. Publishing that as A2A INPUT_REQUIRED closes the stream before
      // acceptInput can run gather and surface openQuestions — suppress it when we will
      // immediately attach the start requirement text.
      boolean attachRequirement = !command.requirementText().isBlank();
      Multi<CreateChainEvent> started =
          mapSignals(
                  taskId,
                  runtime.startOrResume(
                      new StartOrResumeCommand(
                          taskId,
                          binding.productRunId(),
                          profileCatalog.require(
                              binding.runManifest().profileId(),
                              binding.runManifest().profileVersion()),
                          binding.runManifest())))
              .select()
              .where(
                  event ->
                      !(attachRequirement && event instanceof CreateChainEvent.Waiting));
      return started
          .onCompletion()
          .switchTo(
              () -> {
                ProductPipelineRunDocument created =
                    runStore.loadByConversation(taskId).orElse(null);
                if (created != null
                    && created.run().status() == RunStatus.WAITING_FOR_INPUT
                    && attachRequirement) {
                  return mapSignals(
                      taskId,
                      runtime.acceptInput(
                          acceptInput(
                              created.run().runId(),
                              command.requirementText(),
                              command.commandId(),
                              command.origin())));
                }
                return Multi.createFrom().empty();
              });
    }
    return continueExisting(
        taskId, command.requirementText(), command.commandId(), command.origin());
  }

  /** Continues the same durable run with clarification input. */
  public Multi<CreateChainEvent> continueWithInput(ContinueCreateChainCommand command) {
    Objects.requireNonNull(command, "command");
    String taskId = command.taskId();
    selectionService.selectOrCreate(taskId);
    return continueExisting(
        taskId, command.clarificationText(), command.commandId(), command.origin());
  }

  /** Response locale pinned from the conversation's first CREATE prompt. */
  public String responseLocale(String taskId) {
    return selectionService
        .existing(taskId)
        .map(selection -> selection.runManifest().responseLocale())
        .orElse(ResponseLocaleResolver.DEFAULT_LOCALE);
  }

  /** Approves the current expected artifact or recovers a blocked implementation gate. */
  public ApproveCreateChainOutcome approve(ApproveCreateChainArtifactCommand command) {
    Objects.requireNonNull(command, "command");
    String taskId = command.taskId();
    ProductPipelineRunDocument doc =
        runStore
            .loadByConversation(taskId)
            .orElseThrow(() -> new IllegalStateException("no run for taskId " + taskId));
    RunStatus status = doc.run().status();

    // Durable evidence first: a retry after a crash carries the caller's original revision, which
    // is now stale, but the command already applied and must not be re-validated.
    if (approveAlreadyApplied(doc, command)) {
      if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
        ApproveCreateChainOutcome gate =
            autoImplement(taskId, doc, new ArrayList<>(), command.commandId());
        if (gate != null) {
          return gate;
        }
        doc = runStore.loadByConversation(taskId).orElseThrow();
      }
      return new ApproveCreateChainOutcome.Accepted(List.of(), snapshotOf(taskId, doc));
    }
    if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
      return recoverImplementation(taskId, doc, command);
    }
    if (status != RunStatus.WAITING_FOR_APPROVAL) {
      if (alreadyApproved(doc, command)) {
        return new ApproveCreateChainOutcome.DuplicateApproval();
      }
      return new ApproveCreateChainOutcome.NotWaitingForApproval(mapStatus(status));
    }

    Reference expected = approvableReference(doc);
    String expectedType = CreateChainPublicArtifactTypes.toApprovalType(expected.kind());
    Optional<Kind> providedKind = CreateChainPublicArtifactTypes.toKind(command.artifactType());
    if (providedKind.isEmpty()
        || providedKind.get() != expected.kind()
        || !expectedTypeEquals(expectedType, command.artifactType())) {
      return new ApproveCreateChainOutcome.WrongArtifactType(
          expectedType, command.artifactType());
    }
    if (!expected.contentHash().equals(command.artifactHash())) {
      return new ApproveCreateChainOutcome.WrongArtifactHash(
          expected.contentHash(), command.artifactHash());
    }
    if (command.revision() != doc.run().runRevision()) {
      return new ApproveCreateChainOutcome.StaleRevision(
          command.revision(), doc.run().runRevision());
    }

    try {
      List<CreateChainEvent> events =
          collect(
              mapSignals(
                  taskId,
                  runtime.approve(
                      approveStep(
                          doc.run().runId(), expected, doc.run().runRevision(), command))));
      ProductPipelineRunDocument after =
          runStore.loadByConversation(taskId).orElseThrow();
      if (after.run().status() == RunStatus.WAITING_FOR_IMPLEMENT) {
        ApproveCreateChainOutcome gate =
            autoImplement(taskId, after, events, command.commandId());
        if (gate != null) {
          return gate;
        }
        after = runStore.loadByConversation(taskId).orElseThrow();
      }
      return new ApproveCreateChainOutcome.Accepted(events, snapshotOf(taskId, after));
    } catch (RuntimeException e) {
      Throwable root = unwrap(e);
      if (root instanceof StaleApprovalException) {
        return new ApproveCreateChainOutcome.StaleRevision(
            command.revision(),
            runStore
                .loadByConversation(taskId)
                .map(d -> d.run().runRevision())
                .orElse(command.revision()));
      }
      throw e;
    }
  }


  /**
   * Validates an approve command without starting pipeline work. Empty means the command may
   * stream; a present value is a typed rejection.
   */
  public Optional<ApproveCreateChainOutcome> validateApprove(ApproveCreateChainArtifactCommand command) {
    Objects.requireNonNull(command, "command");
    String taskId = command.taskId();
    ProductPipelineRunDocument doc =
        runStore
            .loadByConversation(taskId)
            .orElseThrow(() -> new IllegalStateException("no run for taskId " + taskId));
    RunStatus status = doc.run().status();

    // An already-applied approval is never a protocol error, whatever the run revision is now.
    if (approveAlreadyApplied(doc, command)) {
      return Optional.empty();
    }
    if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
      return validateBlockedRecovery(doc, command);
    }
    if (status != RunStatus.WAITING_FOR_APPROVAL) {
      if (alreadyApproved(doc, command)) {
        return Optional.of(new ApproveCreateChainOutcome.DuplicateApproval());
      }
      return Optional.of(new ApproveCreateChainOutcome.NotWaitingForApproval(mapStatus(status)));
    }

    Reference expected = approvableReference(doc);
    String expectedType = CreateChainPublicArtifactTypes.toApprovalType(expected.kind());
    Optional<Kind> providedKind = CreateChainPublicArtifactTypes.toKind(command.artifactType());
    if (providedKind.isEmpty()
        || providedKind.get() != expected.kind()
        || !expectedTypeEquals(expectedType, command.artifactType())) {
      return Optional.of(
          new ApproveCreateChainOutcome.WrongArtifactType(expectedType, command.artifactType()));
    }
    if (!expected.contentHash().equals(command.artifactHash())) {
      return Optional.of(
          new ApproveCreateChainOutcome.WrongArtifactHash(
              expected.contentHash(), command.artifactHash()));
    }
    if (command.revision() != doc.run().runRevision()) {
      return Optional.of(
          new ApproveCreateChainOutcome.StaleRevision(
              command.revision(), doc.run().runRevision()));
    }
    return Optional.empty();
  }

  /**
   * Streams approval and automatic implementation events without buffering the whole operation.
   * Call {@link #validateApprove} first for typed protocol errors. Blocked
   * {@code WAITING_FOR_IMPLEMENT} recovery streams a single {@link ImplementCommand} without a
   * second runtime approval.
   */
  public Multi<CreateChainEvent> streamApprove(ApproveCreateChainArtifactCommand command) {
    Objects.requireNonNull(command, "command");
    String taskId = command.taskId();
    ProductPipelineRunDocument doc =
        runStore
            .loadByConversation(taskId)
            .orElseThrow(() -> new IllegalStateException("no run for taskId " + taskId));
    // Durable evidence first: the approve step may already have applied before a crash, in which
    // case the compound command resumes at its implement step instead of re-approving.
    if (approveAlreadyApplied(doc, command)) {
      return resumeAfterApprove(taskId, command);
    }
    if (doc.run().status() == RunStatus.WAITING_FOR_IMPLEMENT) {
      return streamBlockedRecovery(taskId, doc, command);
    }
    Reference expected = approvableReference(doc);
    Multi<CreateChainEvent> approved =
        mapSignals(
            taskId,
            runtime.approve(
                approveStep(doc.run().runId(), expected, doc.run().runRevision(), command)));
    return approved
        .onCompletion()
        .switchTo(
            () -> resumeAfterApprove(taskId, command));
  }

  /**
   * Approves without the implement leg.
   *
   * <p>The browser makes writing the chain into the catalog a decision of its own, so the two
   * commands validate separately and a failure of the second leaves the run recoverable at the
   * implementation gate rather than in an ambiguous half-state. A2A keeps the compound {@link
   * #streamApprove} it already relies on.
   */
  public Multi<CreateChainEvent> streamApproveOnly(ApproveCreateChainArtifactCommand command) {
    Objects.requireNonNull(command, "command");
    String taskId = command.taskId();
    ProductPipelineRunDocument doc =
        runStore
            .loadByConversation(taskId)
            .orElseThrow(() -> new IllegalStateException("no run for taskId " + taskId));
    if (approveAlreadyApplied(doc, command)) {
      return Multi.createFrom().empty();
    }
    if (doc.run().status() == RunStatus.WAITING_FOR_IMPLEMENT) {
      return streamBlockedRecovery(taskId, doc, command);
    }
    Reference expected = approvableReference(doc);
    return mapSignals(
        taskId,
        runtime.approve(
            approveStep(doc.run().runId(), expected, doc.run().runRevision(), command)));
  }

  /**
   * Content hash of the plan a run is ready to materialize, or empty when it is not at that gate.
   *
   * <p>Reported to the chat so the implementation gate can be offered as a decision. Nothing here
   * is reachable from the public A2A surface, which refuses a caller-initiated implement action.
   */
  public Optional<String> pendingCreationHash(String taskId) {
    Objects.requireNonNull(taskId, "taskId");
    return runStore
        .loadByConversation(taskId)
        .filter(doc -> doc.run().status() == RunStatus.WAITING_FOR_IMPLEMENT)
        .flatMap(doc -> runtime.approvedPlanContentHash(doc.run().runId()))
        .filter(hash -> !hash.isBlank());
  }

  /**
   * Writes the chain into the catalog, validating its own binding.
   *
   * <p>Refuses anything but the approved plan of a run standing at the implementation gate, so a
   * stale card cannot create a chain from a plan that was revised in the meantime.
   */
  public Multi<CreateChainEvent> streamCreateChain(String taskId, String planHash, long revision) {
    Objects.requireNonNull(taskId, "taskId");
    ProductPipelineRunDocument doc =
        runStore
            .loadByConversation(taskId)
            .orElseThrow(() -> new IllegalStateException("no run for taskId " + taskId));
    Optional<String> expected = pendingCreationHash(taskId);
    if (expected.isEmpty()) {
      return Multi.createFrom()
          .item(
              new CreateChainEvent.Failed(
                  "This run is not waiting to create a chain.", snapshotOf(taskId, doc)));
    }
    if (!expected.get().equals(planHash) || revision != doc.run().runRevision()) {
      return Multi.createFrom()
          .item(
              new CreateChainEvent.Failed(
                  "The approved plan moved on. Nothing was created.", snapshotOf(taskId, doc)));
    }
    return mapSignals(
        taskId,
        runtime.implement(
            new ImplementCommand(doc.run().runId(), planHash, doc.run().runRevision())));
  }

  /**
   * Runs the implement leg of a compound approval. Reached both after a fresh approval and when a
   * retry finds the approve step already applied, so the command always resumes from its first
   * missing internal step.
   */
  private Multi<CreateChainEvent> resumeAfterApprove(
      String taskId, ApproveCreateChainArtifactCommand command) {
    ProductPipelineRunDocument after = runStore.loadByConversation(taskId).orElseThrow();
    if (after.run().status() != RunStatus.WAITING_FOR_IMPLEMENT) {
      return Multi.createFrom().empty();
    }
    Optional<String> hash =
        runtime.approvedPlanContentHash(after.run().runId()).filter(h -> !h.isBlank());
    if (hash.isEmpty()) {
      ApproveCreateChainOutcome blocked =
          autoImplement(taskId, after, new ArrayList<>(), command.commandId());
      if (blocked instanceof ApproveCreateChainOutcome.ImplementationBlocked b) {
        return Multi.createFrom().item(new CreateChainEvent.Waiting(toPending(b.recovery())));
      }
      if (blocked instanceof ApproveCreateChainOutcome.NonRecoverableFailure failure) {
        return Multi.createFrom()
            .item(new CreateChainEvent.Failed(failure.reason(), snapshotOf(taskId, after)));
      }
      return Multi.createFrom().empty();
    }
    lastImplementCount.set(0);
    lastImplementCount.incrementAndGet();
    return mapSignals(
        taskId,
        runtime.implement(
            implementStep(
                after.run().runId(), hash.get(), after.run().runRevision(), command.commandId())));
  }

  private Optional<ApproveCreateChainOutcome> validateBlockedRecovery(
      ProductPipelineRunDocument doc, ApproveCreateChainArtifactCommand command) {
    Optional<Reference> planEvidence = resolvePlanEvidence(doc);
    if (planEvidence.isEmpty()) {
      return Optional.of(
          new ApproveCreateChainOutcome.NonRecoverableFailure(
              "Implementation is blocked and expected plan evidence cannot be restored."));
    }
    Reference expected = planEvidence.get();
    String expectedType = CreateChainPublicArtifactTypes.toApprovalType(expected.kind());
    Optional<Kind> providedKind = CreateChainPublicArtifactTypes.toKind(command.artifactType());
    if (providedKind.isEmpty()
        || providedKind.get() != expected.kind()
        || !expectedTypeEquals(expectedType, command.artifactType())) {
      return Optional.of(
          new ApproveCreateChainOutcome.WrongArtifactType(expectedType, command.artifactType()));
    }
    if (!expected.contentHash().equals(command.artifactHash())) {
      return Optional.of(
          new ApproveCreateChainOutcome.WrongArtifactHash(
              expected.contentHash(), command.artifactHash()));
    }
    if (command.revision() != doc.run().runRevision()) {
      return Optional.of(
          new ApproveCreateChainOutcome.StaleRevision(
              command.revision(), doc.run().runRevision()));
    }
    return Optional.empty();
  }

  private Multi<CreateChainEvent> streamBlockedRecovery(
      String taskId,
      ProductPipelineRunDocument doc,
      ApproveCreateChainArtifactCommand command) {
    Reference expected =
        resolvePlanEvidence(doc)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "blocked recovery requires plan evidence for taskId " + taskId));
    lastImplementCount.set(0);
    lastImplementCount.incrementAndGet();
    return mapSignals(
        taskId,
        runtime.implement(
            implementStep(
                doc.run().runId(),
                expected.contentHash(),
                doc.run().runRevision(),
                command.commandId())));
  }

  private static CreateChainPendingAction toPending(ImplementationBlockedRecovery recovery) {
    if (recovery instanceof ImplementationBlockedRecovery.ApprovePlanEvidence approve) {
      return new CreateChainPendingAction.Approve(
          approve.artifactType(), approve.artifactHash(), approve.revision(), approve.reason());
    }
    if (recovery instanceof ImplementationBlockedRecovery.ClarifyMissingEvidence clarify) {
      return new CreateChainPendingAction.Clarify(clarify.reason(), clarify.missingEvidence());
    }
    throw new IllegalArgumentException("Unhandled recovery: " + recovery.getClass().getName());
  }

    private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      if (current instanceof StaleApprovalException) {
        return current;
      }
      current = current.getCause();
    }
    return current;
  }

  /**
   * Reports whether this exact approval already produced its durable transition.
   *
   * <p>Lets a transport skip pending-action validation for a retry, without moving the decision out
   * of the facade.
   */
  public boolean approvalAlreadyApplied(ApproveCreateChainArtifactCommand command) {
    Objects.requireNonNull(command, "command");
    return runStore
        .loadByConversation(command.taskId())
        .map(doc -> approveAlreadyApplied(doc, command))
        .orElse(false);
  }

  /**
   * Reports whether this exact clarification already produced its durable transition.
   *
   * <p>Lets a transport skip pending-action validation for a retry. After a crash the run has moved
   * past the wait that the clarification satisfied, so re-validating against the current pending
   * action would reject a command that already applied.
   */
  public boolean inputAlreadyApplied(String taskId, String commandId, String text) {
    Objects.requireNonNull(taskId, "taskId");
    if (commandId == null || commandId.isBlank()) {
      return false;
    }
    String safeText = text == null ? "" : text;
    return runStore
        .loadByConversation(taskId)
        .flatMap(
            doc ->
                doc.appliedCommand(
                    stepId(commandId, "accept-input"), payloadHash(Map.of("text", safeText))))
        .isPresent();
  }

  public Optional<CreateChainExecutionSnapshot> snapshot(String taskId) {
    Objects.requireNonNull(taskId, "taskId");
    return runStore.loadByConversation(taskId).map(doc -> snapshotOf(taskId, doc));
  }

  /**
   * Package-visible helper for tests that count internal implement submissions.
   *
   * @return number of {@link ImplementCommand} submissions performed for the last auto-implement
   */
  AtomicInteger lastImplementSubmissions() {
    return lastImplementCount;
  }

  private final AtomicInteger lastImplementCount = new AtomicInteger();

  private Multi<CreateChainEvent> continueExisting(
      String taskId, String text, String commandId, InputOrigin origin) {
    ProductPipelineRunDocument doc =
        runStore
            .loadByConversation(taskId)
            .orElseThrow(() -> new IllegalStateException("no run for taskId " + taskId));
    RunStatus status = doc.run().status();
    if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
      return Multi.createFrom()
          .item(
              new CreateChainEvent.Failed(
                  "Cannot accept clarification while implementation is blocked without recovery.",
                  snapshotOf(taskId, doc)));
    }
    if (status == RunStatus.CHAIN_MATERIALIZED) {
      CreateChainExecutionSnapshot snap = snapshotOf(taskId, doc);
      Optional<CreateChainEvent.ArtifactReady> materialization =
          materializationArtifact(taskId, doc);
      if (materialization.isEmpty()) {
        return Multi.createFrom()
            .item(
                new CreateChainEvent.Failed(
                    "Materialization evidence is missing or malformed.", snap));
      }
      return Multi.createFrom()
          .items(materialization.get(), new CreateChainEvent.Completed(snap));
    }
    if (status == RunStatus.FAILED) {
      return Multi.createFrom()
          .item(
              new CreateChainEvent.Failed(
                  failureMessage(doc), snapshotOf(taskId, doc)));
    }
    if (status == RunStatus.WAITING_FOR_INPUT || status == RunStatus.WAITING_FOR_APPROVAL) {
      return mapSignals(
          taskId,
          runtime.acceptInput(acceptInput(doc.run().runId(), text, commandId, origin)));
    }
    if (status == RunStatus.RUNNING || status == RunStatus.PLAN_APPROVED) {
      CreateRunBinding binding = requireBinding(taskId);
      return mapSignals(
          taskId,
          runtime.startOrResume(
              new StartOrResumeCommand(
                  taskId,
                  binding.productRunId(),
                  profileCatalog.require(
                      binding.runManifest().profileId(), binding.runManifest().profileVersion()),
                  binding.runManifest())));
    }
    return Multi.createFrom().empty();
  }

  private ApproveCreateChainOutcome autoImplement(
      String taskId,
      ProductPipelineRunDocument doc,
      List<CreateChainEvent> events,
      String commandId) {
    lastImplementCount.set(0);
    Optional<String> hash =
        runtime.approvedPlanContentHash(doc.run().runId()).filter(h -> !h.isBlank());
    if (hash.isPresent()) {
      lastImplementCount.incrementAndGet();
      events.addAll(
          collect(
              mapSignals(
                  taskId,
                  runtime.implement(
                      implementStep(
                          doc.run().runId(),
                          hash.get(),
                          doc.run().runRevision(),
                          commandId)))));
      return null;
    }

    Reference planEvidence = resolvePlanEvidence(doc).orElse(null);
    if (planEvidence != null
        && planEvidence.contentHash() != null
        && !planEvidence.contentHash().isBlank()) {
      String publicType = CreateChainPublicArtifactTypes.toApprovalType(planEvidence.kind());
      return new ApproveCreateChainOutcome.ImplementationBlocked(
          new ImplementationBlockedRecovery.ApprovePlanEvidence(
              "Approved plan hash is unavailable for automatic implementation.",
              publicType,
              planEvidence.contentHash(),
              doc.run().runRevision()));
    }

    // Runtime has no legal evidence-recovery transition from WAITING_FOR_IMPLEMENT.
    return new ApproveCreateChainOutcome.NonRecoverableFailure(
        "Approved plan hash is missing and no input-capable recovery transition is available.");
  }

  private ApproveCreateChainOutcome recoverImplementation(
      String taskId,
      ProductPipelineRunDocument doc,
      ApproveCreateChainArtifactCommand command) {
    Optional<Reference> planEvidence = resolvePlanEvidence(doc);
    if (planEvidence.isEmpty()) {
      return new ApproveCreateChainOutcome.NonRecoverableFailure(
          "Implementation is blocked and expected plan evidence cannot be restored.");
    }
    Reference expected = planEvidence.get();
    String expectedType = CreateChainPublicArtifactTypes.toApprovalType(expected.kind());
    Optional<Kind> providedKind = CreateChainPublicArtifactTypes.toKind(command.artifactType());
    if (providedKind.isEmpty()
        || providedKind.get() != expected.kind()
        || !expectedTypeEquals(expectedType, command.artifactType())) {
      return new ApproveCreateChainOutcome.WrongArtifactType(
          expectedType, command.artifactType());
    }
    if (!expected.contentHash().equals(command.artifactHash())) {
      return new ApproveCreateChainOutcome.WrongArtifactHash(
          expected.contentHash(), command.artifactHash());
    }
    if (command.revision() != doc.run().runRevision()) {
      return new ApproveCreateChainOutcome.StaleRevision(
          command.revision(), doc.run().runRevision());
    }

    // Validate evidence, then construct ImplementCommand without re-approving the runtime stage.
    lastImplementCount.set(0);
    lastImplementCount.incrementAndGet();
    List<CreateChainEvent> events =
        collect(
            mapSignals(
                taskId,
                runtime.implement(
                    implementStep(
                        doc.run().runId(),
                        expected.contentHash(),
                        doc.run().runRevision(),
                        command.commandId()))));
    ProductPipelineRunDocument after =
        runStore.loadByConversation(taskId).orElseThrow();
    return new ApproveCreateChainOutcome.Accepted(events, snapshotOf(taskId, after));
  }

  private Multi<CreateChainEvent> mapSignals(String taskId, Multi<PipelineSignal> signals) {
    return signals
        .onItem()
        .transformToMultiAndConcatenate(
            signal -> {
              if (signal instanceof PipelineSignal.Progress progress) {
                String label = sanitizeLabel(progress.label());
                if (label.isBlank()) {
                  return Multi.createFrom().empty();
                }
                return Multi.createFrom().item(new CreateChainEvent.Progress(label));
              }
              if (signal instanceof PipelineSignal.Message message) {
                String text = sanitizeLabel(message.text());
                if (text.isBlank()) {
                  return Multi.createFrom().empty();
                }
                return Multi.createFrom().item(new CreateChainEvent.Message(text));
              }
              if (signal instanceof PipelineSignal.WaitingForInput waiting) {
                CreateChainPendingAction.Clarify clarify =
                    clarifyFromWait(taskId, waiting.prompt());
                return Multi.createFrom().item(new CreateChainEvent.Waiting(clarify));
              }
              if (signal instanceof PipelineSignal.WaitingForApproval waiting) {
                ProductPipelineRunDocument doc =
                    runStore.loadByConversation(taskId).orElse(null);
                long revision = doc == null ? 0L : doc.run().runRevision();
                String type =
                    CreateChainPublicArtifactTypes.toApprovalType(waiting.candidate().kind());
                String prompt = publicPrompt(waiting.prompt());
                Map<String, Object> content =
                    doc == null
                        ? Map.of()
                        : resolveApprovalContent(doc.run().runId(), waiting.candidate());
                if (waiting.candidate().kind() == Kind.REQUIREMENT_BRIEF
                    && (content.isEmpty()
                        || (!content.containsKey("summary") && !content.containsKey("goal")))) {
                  CreateChainExecutionSnapshot snap =
                      doc == null
                          ? new CreateChainExecutionSnapshot(
                              taskId, "", CreateChainExecutionStatus.FAILED, revision, null, "")
                          : snapshotOf(taskId, doc);
                  return Multi.createFrom()
                      .item(
                          new CreateChainEvent.Failed(
                              "Requirement brief evidence cannot be resolved for approval.",
                              snap));
                }
                List<CreateChainEvent> items = new ArrayList<>();
                items.add(
                    new CreateChainEvent.ArtifactReady(
                        type,
                        waiting.candidate().artifactId(),
                        waiting.candidate().contentHash(),
                        revision,
                        content));
                items.add(
                    new CreateChainEvent.Waiting(
                        new CreateChainPendingAction.Approve(
                            type,
                            waiting.candidate().contentHash(),
                            revision,
                            prompt)));
                return Multi.createFrom().iterable(items);
              }
              if (signal instanceof PipelineSignal.WaitingForImplement) {
                // Normal path stays WORKING; facade auto-submits ImplementCommand.
                return Multi.createFrom().item(new CreateChainEvent.Progress("Working"));
              }
              if (signal instanceof PipelineSignal.Failed failed) {
                ProductPipelineRunDocument doc =
                    runStore.loadByConversation(taskId).orElse(null);
                String detail =
                    failed.message() == null || failed.message().isBlank()
                        ? "Something went wrong."
                        : sanitizeLabel(failed.message());
                if (detail.isBlank()) {
                  detail = "Something went wrong.";
                }
                CreateChainExecutionSnapshot snap =
                    doc == null
                        ? new CreateChainExecutionSnapshot(
                            taskId, "", CreateChainExecutionStatus.FAILED, 0L, null, detail)
                        : snapshotOf(taskId, doc);
                return Multi.createFrom().item(new CreateChainEvent.Failed(detail, snap));
              }
              if (signal instanceof PipelineSignal.Completed) {
                ProductPipelineRunDocument doc =
                    runStore.loadByConversation(taskId).orElse(null);
                if (doc == null) {
                  return Multi.createFrom().empty();
                }
                CreateChainExecutionSnapshot snap = snapshotOf(taskId, doc);
                if (snap.status() == CreateChainExecutionStatus.COMPLETED) {
                  Optional<CreateChainEvent.ArtifactReady> materialization =
                      materializationArtifact(taskId, doc);
                  if (materialization.isEmpty()) {
                    return Multi.createFrom()
                        .item(
                            new CreateChainEvent.Failed(
                                "Materialization evidence is missing or malformed.", snap));
                  }
                  List<CreateChainEvent> items = new ArrayList<>();
                  items.add(materialization.get());
                  items.add(new CreateChainEvent.Completed(snap));
                  return Multi.createFrom().iterable(items);
                }
                // PLAN_APPROVED is not a public completion for create-chain@2.
                if (snap.status() == CreateChainExecutionStatus.INPUT_REQUIRED
                    || snap.status() == CreateChainExecutionStatus.FAILED) {
                  return Multi.createFrom().empty();
                }
                return Multi.createFrom().item(new CreateChainEvent.Progress("Working"));
              }
              if (signal instanceof PipelineSignal.SkillProgress skillProgress) {
                String skillId =
                    skillProgress.skillId() == null ? "" : skillProgress.skillId().strip();
                String status =
                    skillProgress.status() == null ? "" : skillProgress.status().strip();
                if (skillId.isBlank() || status.isBlank()) {
                  return Multi.createFrom().empty();
                }
                return Multi.createFrom().item(new CreateChainEvent.SkillProgress(skillId, status));
              }
              return Multi.createFrom().empty();
            });
  }

  private CreateChainExecutionSnapshot snapshotOf(String taskId, ProductPipelineRunDocument doc) {
    RunStatus status = doc.run().status();
    CreateChainPendingAction pending = null;
    CreateChainExecutionStatus mapped = mapStatus(status);
    if (status == RunStatus.WAITING_FOR_APPROVAL) {
      Reference candidate = approvableReference(doc);
      pending =
          new CreateChainPendingAction.Approve(
              CreateChainPublicArtifactTypes.toApprovalType(candidate.kind()),
              candidate.contentHash(),
              doc.run().runRevision(),
              "");
    } else if (status == RunStatus.WAITING_FOR_INPUT) {
      pending = clarifyFromWait(taskId, latestWaitingForInputReason(doc));
    } else if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
      Optional<String> hash =
          runtime.approvedPlanContentHash(doc.run().runId()).filter(h -> !h.isBlank());
      if (hash.isEmpty()) {
        Optional<Reference> planEvidence = resolvePlanEvidence(doc);
        if (planEvidence.isPresent()) {
          Reference plan = planEvidence.get();
          pending =
              new CreateChainPendingAction.Approve(
                  CreateChainPublicArtifactTypes.toApprovalType(plan.kind()),
                  plan.contentHash(),
                  doc.run().runRevision(),
                  "Approved plan hash is unavailable for automatic implementation.");
          mapped = CreateChainExecutionStatus.INPUT_REQUIRED;
        }
      }
    }
    return new CreateChainExecutionSnapshot(
        taskId,
        doc.run().runId(),
        mapped,
        doc.run().runRevision(),
        pending,
        status == RunStatus.FAILED ? failureMessage(doc) : "");
  }

  /**
   * Derives the durable internal step ID for one leg of a compound facade command.
   *
   * <p>Returns {@code null} when the caller supplied no command ID, which leaves the runtime
   * command non-idempotent for callers that do not need replay safety, such as the browser.
   */
  private static String stepId(String commandId, String step) {
    return commandId == null || commandId.isBlank() ? null : commandId + ":" + step;
  }

  private static String payloadHash(Map<String, ?> payload) {
    return CanonicalPayloadHash.sha256Hex(payload);
  }

  /** Builds the {@code accept-input} step of a start or clarification command. */
  private static AcceptInputCommand acceptInput(
      String runId, String text, String commandId, InputOrigin origin) {
    String safeText = text == null ? "" : text;
    return new AcceptInputCommand(
        runId,
        safeText,
        stepId(commandId, "accept-input"),
        payloadHash(Map.of("text", safeText)),
        origin);
  }

  /**
   * Builds the {@code approve} step of an artifact approval command.
   *
   * <p>The payload hash comes from the caller's advertised evidence, not from the resolved target.
   * A retry must derive the same hash after the run has advanced and the approvable reference is
   * gone, and reusing the command ID for a different artifact must still conflict.
   */
  private static ApproveCommand approveStep(
      String runId,
      Reference target,
      long expectedRunRevision,
      ApproveCreateChainArtifactCommand command) {
    return new ApproveCommand(
        runId,
        target,
        expectedRunRevision,
        approveStepId(command),
        approvePayloadHash(command));
  }

  private static String approveStepId(ApproveCreateChainArtifactCommand command) {
    return stepId(command.commandId(), "approve");
  }

  private static String approvePayloadHash(ApproveCreateChainArtifactCommand command) {
    return payloadHash(
        Map.of(
            "artifactType", command.artifactType(),
            "artifactHash", command.artifactHash()));
  }

  /**
   * Reports whether this exact approval already produced its durable transition.
   *
   * <p>Checked before staleness validation: after a crash the run revision has moved on, so
   * re-validating a retry against the newer revision would reject a command that already applied.
   */
  private static boolean approveAlreadyApplied(
      ProductPipelineRunDocument doc, ApproveCreateChainArtifactCommand command) {
    return doc.appliedCommand(approveStepId(command), approvePayloadHash(command)).isPresent();
  }

  /** Builds the {@code implement} step of an approval or blocked-recovery command. */
  private static ImplementCommand implementStep(
      String runId, String approvedPlanContentHash, long expectedRunRevision, String commandId) {
    return new ImplementCommand(
        runId,
        approvedPlanContentHash,
        expectedRunRevision,
        stepId(commandId, "implement"),
        payloadHash(Map.of("approvedPlanContentHash", approvedPlanContentHash)));
  }

  private static CreateChainExecutionStatus mapStatus(RunStatus status) {
    return switch (status) {
      case RUNNING, WAITING_FOR_IMPLEMENT, PLAN_APPROVED -> CreateChainExecutionStatus.WORKING;
      case WAITING_FOR_INPUT, WAITING_FOR_APPROVAL -> CreateChainExecutionStatus.INPUT_REQUIRED;
      case CHAIN_MATERIALIZED -> CreateChainExecutionStatus.COMPLETED;
      case FAILED -> CreateChainExecutionStatus.FAILED;
    };
  }

  private static Reference approvableReference(ProductPipelineRunDocument doc) {
    StageSnapshot stage =
        doc.run().stages().stream()
            .filter(snapshot -> snapshot.stageId().equals(doc.run().currentStageId()))
            .findFirst()
            .orElseThrow();
    if (stage.approvableReference() != null) {
      return stage.approvableReference();
    }
    if (stage.status() == StageStatus.WAITING_FOR_APPROVAL && !stage.outputRefs().isEmpty()) {
      return stage.outputRefs().get(stage.outputRefs().size() - 1);
    }
    throw new IllegalStateException(
        "no approvable reference for stage " + doc.run().currentStageId());
  }

  private Optional<Reference> resolvePlanEvidence(ProductPipelineRunDocument doc) {
    Optional<String> hash = runtime.approvedPlanContentHash(doc.run().runId());
    if (hash.isPresent() && !hash.get().isBlank()) {
      return Optional.of(
          new Reference(Kind.IMPLEMENTATION_PLAN, "approved-plan", hash.get()));
    }
    return doc.run().stages().stream()
        .map(StageSnapshot::approvableReference)
        .filter(Objects::nonNull)
        .filter(ref -> ref.kind() == Kind.IMPLEMENTATION_PLAN)
        .reduce((a, b) -> b)
        .or(
            () ->
                doc.run().stages().stream()
                    .flatMap(stage -> stage.outputRefs().stream())
                    .filter(ref -> ref.kind() == Kind.IMPLEMENTATION_PLAN)
                    .reduce((a, b) -> b));
  }

  private boolean alreadyApproved(
      ProductPipelineRunDocument doc, ApproveCreateChainArtifactCommand command) {
    Optional<Kind> kind = CreateChainPublicArtifactTypes.toKind(command.artifactType());
    if (kind.isEmpty()) {
      return false;
    }
    return doc.run().stages().stream()
        .anyMatch(
            stage ->
                stage.approvableReference() != null
                    && stage.approvableReference().kind() == kind.get()
                    && stage.approvableReference().contentHash().equals(command.artifactHash())
                    && stage.status() == StageStatus.SUCCEEDED);
  }

  private CreateRunBinding requireBinding(String taskId) {
    return bindingStore
        .load(taskId)
        .orElseThrow(() -> new IllegalStateException("missing durable binding for " + taskId));
  }

  private static boolean expectedTypeEquals(String expectedPublicType, String provided) {
    Optional<Kind> expectedKind = CreateChainPublicArtifactTypes.toKind(expectedPublicType);
    Optional<Kind> providedKind = CreateChainPublicArtifactTypes.toKind(provided);
    return expectedKind.isPresent()
        && providedKind.isPresent()
        && expectedKind.get() == providedKind.get();
  }

  private static String failureMessage(ProductPipelineRunDocument doc) {
    if (doc.attempts().isEmpty()) {
      return "Product CREATE run failed.";
    }
    StageAttempt last = doc.attempts().get(doc.attempts().size() - 1);
    String stage = last.stageId() == null || last.stageId().isBlank() ? "" : " at " + last.stageId();
    // sanitizeLabel drops the whole text when it carries a state-machine token, and failure
    // evidence almost always leads with one. Strip the tokens instead: without the detail, the
    // caller cannot tell a template defect from an outage.
    String detail = withoutInternalTokens(last.failureEvidence());
    return detail.isBlank()
        ? "Product CREATE run failed" + stage + "."
        : "Product CREATE run failed" + stage + ": " + detail;
  }

  /** Keeps diagnosable failure text while removing internal tokens and storage references. */
  private static String withoutInternalTokens(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    if (text.contains("s3://")
        || text.contains("product-pipeline-")
        || text.contains("compiler-artifacts/")) {
      return "";
    }
    return INTERNAL_TOKEN.matcher(text).replaceAll("").replaceFirst("^[\\s:]+", "").strip();
  }

  private static String publicPrompt(String prompt) {
    return sanitizeLabel(PipelineGates.strip(prompt)).strip();
  }

  /**
   * Prefer the stage wait prompt; when it is blank (discovery leaves reason empty so chat does not
   * glue jargon to streamed tokens), surface draft {@code openQuestions} so A2A clients see what is
   * missing.
   */
  private CreateChainPendingAction.Clarify clarifyFromWait(String taskId, String waitPrompt) {
    String gateId = PipelineGates.gateOf(waitPrompt).orElse("");
    String prompt = publicPrompt(waitPrompt);
    if (!prompt.isBlank()) {
      if (PipelineGates.MAPPING_GAP.equals(gateId)) {
        MappingGapWait.View view = MappingGapWait.parse(prompt);
        return new CreateChainPendingAction.Clarify(
            view.question(), view.missingEdges(), gateId);
      }
      if (PipelineGates.OWNER_CHOICE.equals(gateId)) {
        return new CreateChainPendingAction.Clarify(
            prompt, PipelineGates.ownerCandidatesOf(waitPrompt), gateId);
      }
      if (PipelineGates.STAGE_INTERNAL_FAILURE.equals(gateId)) {
        return new CreateChainPendingAction.Clarify(
            prompt, PipelineGates.internalFailureActionsOf(waitPrompt), gateId);
      }
      if (PipelineGates.STAGE_ESCALATED.equals(gateId)) {
        return new CreateChainPendingAction.Clarify(
            prompt, PipelineGates.escalatedActionsOf(waitPrompt), gateId);
      }
      return new CreateChainPendingAction.Clarify(prompt, List.of(), gateId);
    }
    Optional<RequirementDraft> draft =
        draftStore == null || taskId == null || taskId.isBlank()
            ? Optional.empty()
            : draftStore.get(taskId);
    if (draft.isPresent() && !draft.get().openQuestions().isEmpty()) {
      List<String> questions = draft.get().openQuestions();
      return new CreateChainPendingAction.Clarify(String.join("\n", questions), questions);
    }
    return new CreateChainPendingAction.Clarify(DEFAULT_CLARIFY_REASON, List.of());
  }

  private static String latestWaitingForInputReason(ProductPipelineRunDocument doc) {
    return doc.transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((a, b) -> b)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElse("");
  }

  private static String sanitizeLabel(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    if (INTERNAL_TOKEN.matcher(text).find()) {
      return "";
    }
    if (text.contains("s3://")
        || text.contains("product-pipeline-")
        || text.contains("compiler-artifacts/")) {
      return "";
    }
    return text;
  }


  private Optional<CreateChainEvent.ArtifactReady> materializationArtifact(
      String taskId, ProductPipelineRunDocument doc) {
    Optional<ChainCatalogFacts> facts =
        runtime.latestCatalogChainSnapshot(doc.run().runId());
    if (facts.isEmpty()) {
      return Optional.empty();
    }
    ChainCatalogFacts catalog = facts.get();
    if (catalog.chainId() == null || catalog.chainId().isBlank()) {
      return Optional.empty();
    }
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("chainId", catalog.chainId());
    if (catalog.chainName() != null && !catalog.chainName().isBlank()) {
      content.put("chainName", catalog.chainName());
    }
    content.put("outcome", "materialized");
    if (catalog.lifecycleStatus() != null && !catalog.lifecycleStatus().isBlank()) {
      content.put("status", catalog.lifecycleStatus());
    }
    String artifactId = "materialization-" + catalog.chainId();
    String hash = CanonicalPayloadHash.sha256Hex(content);
    return Optional.of(
        new CreateChainEvent.ArtifactReady(
            CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
            artifactId,
            hash,
            doc.run().runRevision(),
            content));
  }

  private Map<String, Object> resolveApprovalContent(String runId, Reference candidate) {
    Map<String, Object> content = new LinkedHashMap<>();
    if (candidate == null || artifactStore == null) {
      return content;
    }
    Optional<Revision> revision = artifactStore.get(runId, candidate);
    if (revision.isEmpty()) {
      return content;
    }
    try {
      if (candidate.kind() == Kind.REQUIREMENT_BRIEF) {
        org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief brief =
            artifactStore.payload(
                revision.get(),
                org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief.class);
        if (brief != null) {
          if (brief.summary() != null && !brief.summary().isBlank()) {
            content.put("summary", truncate(brief.summary(), 512));
          }
          if (brief.goal() != null && !brief.goal().isBlank()) {
            content.put("goal", truncate(brief.goal(), 512));
          }
          if (!content.isEmpty()) {
            content.put("title", "Requirement brief");
          }
        }
      } else if (candidate.kind() == Kind.IDS_DOCUMENT) {
        IdsDocument document = artifactStore.payload(revision.get(), IdsDocument.class);
        if (document != null && document.markdown() != null && !document.markdown().isBlank()) {
          content.put("markdown", document.markdown());
          content.put("summary", truncate(document.markdown(), 512));
          content.put("title", "Integration design");
        }
      } else if (candidate.kind() == Kind.IMPLEMENTATION_PLAN) {
        ImplementationPlan plan =
            artifactStore.payload(revision.get(), ImplementationPlan.class);
        if (plan != null && plan.planText() != null) {
          String chatPlan = ImplementationPlanChatView.forChatReview(plan.planText());
          if (!chatPlan.isBlank()) {
            content.put("planText", chatPlan);
            content.put("summary", truncate(chatPlan, 512));
            content.put("title", "Implementation plan");
          }
        }
      }
    } catch (RuntimeException ignored) {
      // Scripted stubs may store Map payloads; omit reviewable body rather than failing the wait.
    }
    return Map.copyOf(content);
  }

  private static String truncate(String text, int max) {
    String stripped = text.strip();
    return stripped.length() <= max ? stripped : stripped.substring(0, max);
  }

  private static List<CreateChainEvent> collect(Multi<CreateChainEvent> events) {
    return new ArrayList<>(events.collect().asList().await().indefinitely());
  }
}
