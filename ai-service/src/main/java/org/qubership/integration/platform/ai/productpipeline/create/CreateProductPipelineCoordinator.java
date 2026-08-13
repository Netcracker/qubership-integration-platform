package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainMaterializedSummary;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.llm.agent.GateReplyAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputIdsPathPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Browser chat coordinator for product CREATE turns.
 *
 * <p>Maps {@code ChatRequest}/{@code ChatEvent} onto {@link ProductPipelineRuntime} commands and
 * durable run evidence. Transport-neutral callers (A2A) must use {@link
 * org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade}
 * instead of this coordinator. Browser convenience approval ({@link #approveCurrent} and free-form
 * Agree text) stays here.
 */
@ApplicationScoped
public class CreateProductPipelineCoordinator {

  private final CreateRunSelectionService selectionService;
  private final CreateRunBindingStore bindingStore;
  private final ProductPipelineRuntime runtime;
  private final ProductPipelineRunStore runStore;
  private final ProductPipelineProfileCatalog profileCatalog;
  private final ApprovalPrompts approvalPrompts;
  private final GateReplyAgent gateReplyAgent;

  /** Both null in unit tests, which build the coordinator without CDI. */
  @Inject ApprovalQuestionStore approvalQuestions;

  @Inject CreateChainApplicationFacade facade;

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(CreateProductPipelineCoordinator.class);

  @Inject
  public CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ApprovalPromptAgent approvalPromptAgent,
      GateReplyAgent gateReplyAgent) {
    this(
        selectionService,
        bindingStore,
        runtime,
        runStore,
        profileCatalog,
        new ApprovalPrompts(approvalPromptAgent),
        gateReplyAgent);
  }

  /** Test helper without approval-prompt LLM (English fallback CTAs). */
  public CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog) {
    this(selectionService, bindingStore, runtime, runStore, profileCatalog, new ApprovalPrompts());
  }

  CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ApprovalPrompts approvalPrompts) {
    this(selectionService, bindingStore, runtime, runStore, profileCatalog, approvalPrompts, null);
  }

  CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      ApprovalPrompts approvalPrompts,
      GateReplyAgent gateReplyAgent) {
    this.gateReplyAgent = gateReplyAgent;
    this.selectionService = Objects.requireNonNull(selectionService, "selectionService");
    this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
    this.approvalPrompts = Objects.requireNonNull(approvalPrompts, "approvalPrompts");
  }

  CreateProductPipelineCoordinator(
      CreateRunSelectionService selectionService,
      CreateRunBindingStore bindingStore,
      ProductPipelineRuntime runtime,
      ProductPipelineRunStore runStore,
      ProductPipelineProfileCatalog profileCatalog,
      boolean unused) {
    this(selectionService, bindingStore, runtime, runStore, profileCatalog);
  }

  public Optional<ProductPipelineRunDocument> loadRun(String conversationId) {
    return runStore.loadByConversation(conversationId);
  }

  public Multi<ChatEvent> handle(ChatRequest request, String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    selectionService.selectOrCreate(conversationId);
    CreateRunBinding binding =
        bindingStore
            .load(conversationId)
            .orElseThrow(
                () -> new IllegalStateException("missing durable binding for " + conversationId));

    Optional<ProductPipelineRunDocument> existing = runStore.loadByConversation(conversationId);
    String text = request != null ? request.getEffectiveUserText() : "";
    ScenarioType hint = request != null ? request.getScenarioHint() : null;

    if (existing.isEmpty()) {
      Multi<ChatEvent> started =
          mapSignals(
              runtime.startOrResume(
                  new StartOrResumeCommand(
                      conversationId,
                      binding.productRunId(),
                      profileCatalog.require(
                          binding.runManifest().profileId(), binding.runManifest().profileVersion()),
                      binding.runManifest())),
              conversationId);
      return started
          .onCompletion()
          .switchTo(
              () -> {
                ProductPipelineRunDocument created =
                    runStore.loadByConversation(conversationId).orElse(null);
                if (created != null
                    && created.run().status() == RunStatus.WAITING_FOR_INPUT
                    && text != null
                    && !text.isBlank()) {
                  return mapSignals(
                      runtime.acceptInput(
                          new AcceptInputCommand(created.run().runId(), text)),
                      conversationId);
                }
                return Multi.createFrom().empty();
              });
    }

    ProductPipelineRunDocument doc = existing.get();
    RunStatus status = doc.run().status();
    if (status == RunStatus.PLAN_APPROVED) {
      if (hint == ScenarioType.IMPLEMENT_CHAIN
          || (text != null && text.toLowerCase(Locale.ROOT).contains("implement"))) {
        return Multi.createFrom()
            .item(
                ChatEvent.token(
                    "This CREATE flow stops after the plan is approved. Implement and assembly are"
                        + " out of scope for this slice."));
      }
      return Multi.createFrom()
          .item(ChatEvent.token("Implementation plan approved."));
    }
    if (status == RunStatus.CHAIN_MATERIALIZED) {
      return Multi.createFrom()
          .item(ChatEvent.token(chainMaterializedChatSummary(doc.run().runId())));
    }
    if (status == RunStatus.FAILED) {
      return Multi.createFrom()
          .item(
              ChatEvent.error(
                  "Product CREATE run failed"
                      + (doc.attempts().isEmpty()
                          ? ""
                          : ": " + doc.attempts().get(doc.attempts().size() - 1).failureEvidence())));
    }
    if (status == RunStatus.WAITING_FOR_IMPLEMENT) {
      return Multi.createFrom().item(creationDecision(conversationId, doc));
    }
    if (status == RunStatus.WAITING_FOR_APPROVAL && approvesCurrentCandidate(conversationId, doc, text)) {
      // A model-driven approval never materializes: the run stops at the implement gate, which is
      // a decision of its own.
      Reference candidate = approvableReference(doc);
      return mapSignals(
              runtime.approve(
                  new ApproveCommand(doc.run().runId(), candidate, doc.run().runRevision())),
              conversationId)
          .onCompletion()
          .switchTo(() -> autoImplementAfterPlanApproval(conversationId, false));
    }
    if (status == RunStatus.WAITING_FOR_INPUT || status == RunStatus.WAITING_FOR_APPROVAL) {
      return mapSignals(
          runtime.acceptInput(new AcceptInputCommand(doc.run().runId(), text == null ? "" : text)),
          conversationId);
    }
    if (status == RunStatus.RUNNING) {
      // Resume an in-flight run (e.g. previous SSE aborted mid-stage). Do not acceptInput —
      // that is only valid at WAITING_FOR_INPUT / WAITING_FOR_APPROVAL.
      return mapSignals(
          runtime.startOrResume(
              new StartOrResumeCommand(
                  conversationId,
                  binding.productRunId(),
                  profileCatalog.require(
                      binding.runManifest().profileId(), binding.runManifest().profileVersion()),
                  binding.runManifest())),
          conversationId);
    }
    return mapSignals(
        runtime.startOrResume(
            new StartOrResumeCommand(
                conversationId,
                binding.productRunId(),
                profileCatalog.require(
                    binding.runManifest().profileId(), binding.runManifest().profileVersion()),
                binding.runManifest())),
        conversationId);
  }

  public Multi<ChatEvent> approveCurrent(String conversationId) {
    ProductPipelineRunDocument doc =
        loadRun(conversationId)
            .orElseThrow(() -> new IllegalStateException("no run for " + conversationId));
    Reference candidate = approvableReference(doc);
    return mapSignals(
            runtime.approve(
                new ApproveCommand(doc.run().runId(), candidate, doc.run().runRevision())),
            conversationId)
        .onCompletion()
        .switchTo(() -> autoImplementAfterPlanApproval(conversationId, true));
  }

  /**
   * After the planning stage is approved, create-chain waits at an implementation gate. Chat users
   * should not see content hashes — Agree on the plan continues straight into materialization.
   */
  private Multi<ChatEvent> autoImplementAfterPlanApproval(
      String conversationId, boolean confirmedLiterally) {
    ProductPipelineRunDocument after = runStore.loadByConversation(conversationId).orElse(null);
    if (after == null || after.run().status() != RunStatus.WAITING_FOR_IMPLEMENT) {
      return Multi.createFrom().empty();
    }
    if (!confirmedLiterally) {
      // Writing a chain into the catalog is irreversible and nothing here removes it again, so it
      // is never a side effect of approving the plan. The run stays at the implementation gate and
      // the reader is offered creation as a decision of its own.
      LOG.infof(
          "Plan approved; holding at the implementation gate for a creation command runId=%s",
          after.run().runId());
      return Multi.createFrom().item(creationDecision(conversationId, after));
    }
    Optional<String> hash =
        runtime.approvedPlanContentHash(after.run().runId()).filter(h -> !h.isBlank());
    if (hash.isEmpty()) {
      return Multi.createFrom().item(creationDecision(conversationId, after));
    }
    return mapSignals(
        runtime.implement(
            new ImplementCommand(
                after.run().runId(), hash.get(), after.run().runRevision())),
        conversationId);
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

  private Multi<ChatEvent> mapSignals(Multi<PipelineSignal> signals, String conversationId) {
    return signals
        .onItem()
        .transformToMultiAndConcatenate(
            signal -> {
              if (signal instanceof PipelineSignal.Message message) {
                return Multi.createFrom().item(ChatEvent.token(message.text()));
              }
              if (signal instanceof PipelineSignal.Progress progress) {
                return Multi.createFrom()
                    .item(
                        ChatEvent.step(
                            "stage:" + progress.stageId(),
                            "pipeline",
                            "running",
                            progress.label(),
                            null));
              }
              if (signal instanceof PipelineSignal.SkillProgress skillProgress) {
                return Multi.createFrom()
                    .item(
                        ChatEvent.skillStep(skillProgress.skillId(), skillProgress.status()));
              }
              if (signal instanceof PipelineSignal.WaitingForInput waiting) {
                // A wait that names a gate becomes that gate's card. Everything else is ordinary
                // prose, and a blank wait is bootstrap silence or a question discovery already
                // streamed: no card mid-stream (startOrResume hits this before acceptInput while
                // skills still run). ChatExecutionService.openGate at turn end owns durable cards.
                Optional<String> gate = PipelineGates.gateOf(waiting.prompt());
                String prompt = chatWaitPrompt(waiting.prompt());
                if (gate.isPresent()) {
                  return Multi.createFrom()
                      .item(gateDecision(conversationId, gate.get(), prompt.strip()));
                }
                if (!prompt.isBlank()) {
                  return Multi.createFrom().item(ChatEvent.token(prompt));
                }
                return Multi.createFrom().empty();
              }
              if (signal instanceof PipelineSignal.WaitingForApproval waiting) {
                return Multi.createFrom().item(approvalDecision(conversationId, waiting));
              }
              if (signal instanceof PipelineSignal.WaitingForImplement) {
                // Chat auto-continues into implement; the materialization Message is the user-facing
                // summary. Do not show an intermediate "Creating the chain..." banner.
                return Multi.createFrom().empty();
              }
              if (signal instanceof PipelineSignal.Failed failed) {
                // Do not prefix StageOutcomeClass enum names into chat errors.
                String detail =
                    failed.message() == null || failed.message().isBlank()
                        ? "Something went wrong."
                        : failed.message();
                return Multi.createFrom().item(ChatEvent.error(detail));
              }
              if (signal instanceof PipelineSignal.Completed) {
                // CHAIN_MATERIALIZED: summary already streamed as PipelineSignal.Message.
                // Do not leak RunStatus enum names (CHAIN_MATERIALIZED, PLAN_APPROVED, …) into chat.
                return Multi.createFrom().empty();
              }
              return Multi.createFrom().empty();
            });
  }

  /**
   * Turns an approval wait into the decision card the reader answers.
   *
   * <p>Emitted even when the wait carries no prompt: a gate the reader cannot see is worse than a
   * card with a short question. Durable question text arrives with the pending-decision query.
   */
  private ChatEvent approvalDecision(
      String conversationId, PipelineSignal.WaitingForApproval waiting) {
    long revision =
        runStore
            .loadByConversation(conversationId)
            .map(doc -> doc.run().runRevision())
            .orElse(0L);
    String hash = waiting.candidate().contentHash();
    String question = durableQuestion(conversationId, hash, waiting.prompt());
    PendingAction pending =
        new CreateChainPendingAction.Approve(
            CreateChainPublicArtifactTypes.toApprovalType(waiting.candidate().kind()),
            hash,
            revision,
            question);
    return ChatEvent.decision(pending, revision, "");
  }

  /**
   * Question for this gate: the fresh prompt when the run just stopped, the stored one when it was
   * resumed. A resumed wait carries no prompt, and a blank card tells the reader nothing.
   */
  private String durableQuestion(String conversationId, String artifactHash, String prompt) {
    String question = chatWaitPrompt(prompt).strip();
    if (approvalQuestions == null) {
      return question;
    }
    if (question.isBlank()) {
      return approvalQuestions.find(conversationId, artifactHash).orElse("");
    }
    approvalQuestions.save(conversationId, artifactHash, question);
    return question;
  }

  /**
   * Formats a wait prompt for chat: blank stays blank; otherwise prefixes a blank line so the text
   * does not glue to prior streamed tokens.
   */
  private static String chatWaitPrompt(String prompt) {
    String stripped = PipelineGates.strip(prompt);
    if (stripped.isBlank()) {
      return "";
    }
    String trimmed = stripped;
    if (trimmed.startsWith("\n")) {
      return trimmed;
    }
    return "\n\n" + trimmed;
  }

  /** IDS path choice as a Yes / No card (not a free-text clarify field). */
  /**
   * A named gate as its card: the question the run authored, plus the actions that gate accepts.
   *
   * <p>Mapping gaps pack their edges into the wait so a resumed card can list them again; every
   * other gate carries its question alone.
   */
  private ChatEvent gateDecision(String conversationId, String gateId, String prompt) {
    long revision =
        runStore
            .loadByConversation(conversationId)
            .map(doc -> doc.run().runRevision())
            .orElse(0L);
    if (PipelineGates.MAPPING_GAP.equals(gateId)) {
      DesignInputIdsPathPrompts.MappingGapView view =
          DesignInputIdsPathPrompts.parseMappingGapWait(prompt);
      return ChatEvent.decision(
          new CreateChainPendingAction.Clarify(view.question(), view.missingEdges(), gateId),
          revision,
          view.question(),
          ChatEvent.actionsForGate(gateId));
    }
    return ChatEvent.decision(
        new CreateChainPendingAction.Clarify(prompt, List.of(), gateId),
        revision,
        prompt,
        ChatEvent.actionsForGate(gateId));
  }

  /**
   * The implementation gate as a card offering creation alone.
   *
   * <p>The question is authored in the language of the conversation and stored with the run, so a
   * re-fetch after a reload finds the same text.
   */
  private ChatEvent creationDecision(String conversationId, ProductPipelineRunDocument doc) {
    String hash = runtime.approvedPlanContentHash(doc.run().runId()).orElse("");
    String question = approvalPrompts.implementContinuationPrompt(languageReference(doc));
    if (!hash.isBlank() && approvalQuestions != null) {
      approvalQuestions.save(conversationId, hash, question);
    }
    return ChatEvent.createChainDecision(
        CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
        hash,
        doc.run().runRevision(),
        question);
  }

  private String languageReference(ProductPipelineRunDocument doc) {
    if (doc == null) {
      return "";
    }
    // Prefer durable transition reason from the last approval wait when present; otherwise empty
    // so ApprovalPrompts uses its English fallback after LLM failure.
    return doc.transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_APPROVAL)
        .reduce((a, b) -> b)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .filter(reason -> !reason.isBlank() && !reason.equals("approved"))
        .orElse("");
  }

  private String chainMaterializedChatSummary(String runId) {
    Optional<ChainCatalogFacts> facts = runtime.latestCatalogChainSnapshot(runId);
    if (facts.isPresent()) {
      return ChainMaterializedSummary.format(facts.get());
    }
    return "Chain is ready.";
  }

  /**
   * Reads a typed reply at an open gate and reports whether it approves the current candidate.
   *
   * <p>No pattern match decides this. The reply reaches a model that can only express an approval
   * by naming the artifact type, hash, and revision, and the facade refuses a binding that does not
   * match the open gate — so a qualified reply, a reply about later work, or a stale one leaves the
   * run where it is and travels on as input for the stage.
   */
  private boolean approvesCurrentCandidate(
      String conversationId, ProductPipelineRunDocument doc, String text) {
    if (gateReplyAgent == null || text == null || text.isBlank()) {
      return false;
    }
    Reference candidate = approvableReference(doc);
    String artifactType = CreateChainPublicArtifactTypes.toApprovalType(candidate.kind());
    AtomicReference<ApproveCandidateTool.Binding> named = new AtomicReference<>();
    try (AutoCloseable ignored = ApproveCandidateTool.capture(named)) {
      gateReplyAgent.interpretReply(
          "gate:" + conversationId,
          artifactType,
          candidate.contentHash(),
          doc.run().runRevision(),
          text);
    } catch (Exception ex) {
      LOG.warnf(ex, "Gate reply agent failed; treating the reply as not an approval");
      return false;
    }
    ApproveCandidateTool.Binding binding = named.get();
    if (binding == null) {
      return false;
    }
    if (facade == null) {
      return false;
    }
    return facade
        .validateApprove(
            new ApproveCreateChainArtifactCommand(
                conversationId, binding.artifactType(), binding.artifactHash(), binding.revision()))
        .isEmpty();
  }
}
