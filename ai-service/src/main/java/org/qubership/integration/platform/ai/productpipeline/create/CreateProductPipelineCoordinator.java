package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainMaterializedSummary;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.llm.agent.GateReplyAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.ExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Browser chat coordinator for product CREATE turns.
 *
 * <p>Interprets free-form replies and maps {@link CreateChainEvent} frames to {@link ChatEvent}.
 * Lifecycle commands go through {@link CreateChainApplicationFacade}, the same seam A2A uses.
 */
@ApplicationScoped
public class CreateProductPipelineCoordinator {

  private final CreateChainApplicationFacade facade;
  private final ProductPipelineRunStore runStore;
  private final ApprovalPrompts approvalPrompts;
  private final GateReplyAgent gateReplyAgent;
  private final ApprovalQuestionStore approvalQuestions;

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(CreateProductPipelineCoordinator.class);

  @Inject
  public CreateProductPipelineCoordinator(
      CreateChainApplicationFacade facade,
      ProductPipelineRunStore runStore,
      ApprovalPromptAgent approvalPromptAgent,
      GateReplyAgent gateReplyAgent,
      ApprovalQuestionStore approvalQuestions) {
    this(
        facade,
        runStore,
        new ApprovalPrompts(approvalPromptAgent),
        gateReplyAgent,
        approvalQuestions);
  }

  /** Test helper without approval-prompt LLM (English fallback CTAs). */
  public CreateProductPipelineCoordinator(
      CreateChainApplicationFacade facade, ProductPipelineRunStore runStore) {
    this(facade, runStore, new ApprovalPrompts(), null, null);
  }

  CreateProductPipelineCoordinator(
      CreateChainApplicationFacade facade,
      ProductPipelineRunStore runStore,
      ApprovalPrompts approvalPrompts,
      GateReplyAgent gateReplyAgent) {
    this(facade, runStore, approvalPrompts, gateReplyAgent, null);
  }

  CreateProductPipelineCoordinator(
      CreateChainApplicationFacade facade,
      ProductPipelineRunStore runStore,
      ApprovalPrompts approvalPrompts,
      GateReplyAgent gateReplyAgent,
      ApprovalQuestionStore approvalQuestions) {
    this.facade = Objects.requireNonNull(facade, "facade");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.approvalPrompts = Objects.requireNonNull(approvalPrompts, "approvalPrompts");
    this.gateReplyAgent = gateReplyAgent;
    this.approvalQuestions = approvalQuestions;
  }

  public Optional<ProductPipelineRunDocument> loadRun(String conversationId) {
    return runStore.loadByConversation(conversationId);
  }

  public Multi<ChatEvent> handle(ChatRequest request, String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    String text = request != null ? request.getEffectiveUserText() : "";
    ScenarioType hint = request != null ? request.getScenarioHint() : null;

    Optional<ProductPipelineRunDocument> existing = runStore.loadByConversation(conversationId);
    if (existing.isEmpty()) {
      return mapEvents(
          facade.start(new StartCreateChainCommand(conversationId, text)), conversationId);
    }

    ProductPipelineRunDocument doc = existing.get();
    RunStatus status = doc.run().status();
    if (status == RunStatus.PLAN_APPROVED) {
      if (isImplementIntent(hint)) {
        return Multi.createFrom()
            .item(
                ChatEvent.token(
                    "This CREATE flow stops after the plan is approved. Implement and assembly are"
                        + " out of scope for this slice."));
      }
      return Multi.createFrom().item(ChatEvent.token("Implementation plan approved."));
    }
    if (status == RunStatus.WAITING_FOR_IMPLEMENT
        || facade.pendingCreationHash(conversationId).isPresent()) {
      if (isImplementIntent(hint)) {
        return createChain(conversationId);
      }
      return Multi.createFrom().item(creationDecision(conversationId, doc));
    }
    if (status == RunStatus.WAITING_FOR_APPROVAL && approvesCurrentCandidate(conversationId, text)) {
      return approveVisibleCandidate(conversationId, doc);
    }
    return mapEvents(
        facade.start(new StartCreateChainCommand(conversationId, text)), conversationId);
  }

  public Multi<ChatEvent> approveCurrent(String conversationId) {
    ProductPipelineRunDocument doc =
        loadRun(conversationId)
            .orElseThrow(() -> new IllegalStateException("no run for " + conversationId));
    return approveVisibleCandidate(conversationId, doc);
  }

  private Multi<ChatEvent> approveVisibleCandidate(
      String conversationId, ProductPipelineRunDocument doc) {
    CreateChainPendingAction.Approve pending =
        visibleApproval(conversationId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no approvable reference for stage " + doc.run().currentStageId()));
    return mapEvents(
            facade.streamApproveOnly(
                new ApproveCreateChainArtifactCommand(
                    conversationId,
                    pending.artifactType(),
                    pending.artifactHash(),
                    pending.revision())),
            conversationId)
        .onCompletion()
        .switchTo(() -> holdAtImplementationGate(conversationId));
  }

  private Multi<ChatEvent> holdAtImplementationGate(String conversationId) {
    ProductPipelineRunDocument after = runStore.loadByConversation(conversationId).orElse(null);
    if (after == null || after.run().status() != RunStatus.WAITING_FOR_IMPLEMENT) {
      return Multi.createFrom().empty();
    }
    LOG.infof(
        "Plan approved; holding at the implementation gate for a creation command runId=%s",
        after.run().runId());
    return Multi.createFrom().item(creationDecision(conversationId, after));
  }

  private Multi<ChatEvent> createChain(String conversationId) {
    Optional<String> hash = facade.pendingCreationHash(conversationId);
    if (hash.isEmpty()) {
      ProductPipelineRunDocument doc = runStore.loadByConversation(conversationId).orElse(null);
      if (doc == null) {
        return Multi.createFrom().empty();
      }
      return Multi.createFrom().item(creationDecision(conversationId, doc));
    }
    long revision =
        facade.snapshot(conversationId).map(ExecutionSnapshot::revision).orElse(0L);
    return mapEvents(
        facade.streamCreateChain(conversationId, hash.get(), revision), conversationId);
  }

  private Multi<ChatEvent> mapEvents(Multi<CreateChainEvent> events, String conversationId) {
    return events
        .onItem()
        .transformToMultiAndConcatenate(event -> toChatEvent(conversationId, event));
  }

  private Multi<ChatEvent> toChatEvent(String conversationId, CreateChainEvent event) {
    if (event instanceof CreateChainEvent.Message message) {
      return Multi.createFrom().item(ChatEvent.token(message.text()));
    }
    if (event instanceof CreateChainEvent.Progress progress) {
      if (progress.label().isBlank() || "Working".equals(progress.label())) {
        return Multi.createFrom().empty();
      }
      return Multi.createFrom()
          .item(
              ChatEvent.step(
                  "stage:" + progress.label(), "pipeline", "running", progress.label(), null));
    }
    if (event instanceof CreateChainEvent.SkillProgress skill) {
      if (skill.skillId().isBlank() || skill.status().isBlank()) {
        return Multi.createFrom().empty();
      }
      return Multi.createFrom().item(ChatEvent.skillStep(skill.skillId(), skill.status()));
    }
    if (event instanceof CreateChainEvent.Waiting waiting) {
      return waitingToChat(conversationId, waiting);
    }
    if (event instanceof CreateChainEvent.Failed failed) {
      return Multi.createFrom().item(ChatEvent.error(failed.message()));
    }
    if (event instanceof CreateChainEvent.ArtifactReady artifact) {
      String summary = chainSummaryFromArtifact(artifact);
      if (summary == null) {
        return Multi.createFrom().empty();
      }
      return Multi.createFrom().item(ChatEvent.token(summary));
    }
    return Multi.createFrom().empty();
  }

  private Multi<ChatEvent> waitingToChat(String conversationId, CreateChainEvent.Waiting waiting) {
    PendingAction pending = waiting.pendingAction();
    if (pending instanceof CreateChainPendingAction.Clarify clarify
        && clarify.gateId().isBlank()
        && clarify.missingEvidence().isEmpty()
        && (clarify.reason().isBlank()
            || "Additional input is required.".equals(clarify.reason()))) {
      return Multi.createFrom().empty();
    }
    long revision = revisionOf(conversationId, waiting);
    if (pending instanceof CreateChainPendingAction.Approve approve) {
      String question = durableQuestion(conversationId, approve.artifactHash(), approve.prompt());
      return Multi.createFrom().item(ChatEvent.decision(pending, revision, question));
    }
    if (pending instanceof CreateChainPendingAction.Clarify clarify) {
      String question = chatWaitPrompt(clarify.reason());
      return Multi.createFrom()
          .item(
              ChatEvent.decision(
                  pending, revision, question, ChatEvent.actionsForGate(clarify.gateId())));
    }
    return Multi.createFrom().item(ChatEvent.decision(pending, revision, ""));
  }

  private long revisionOf(String conversationId, CreateChainEvent.Waiting waiting) {
    if (waiting.pendingAction() instanceof PendingAction.Approve approve) {
      return approve.revision();
    }
    return facade.snapshot(conversationId).map(ExecutionSnapshot::revision).orElse(0L);
  }

  private ChatEvent creationDecision(String conversationId, ProductPipelineRunDocument doc) {
    String hash = facade.pendingCreationHash(conversationId).orElse("");
    String question =
        approvalPrompts.implementContinuationPrompt(
            facade.responseLocale(conversationId), languageReference(doc));
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
    return doc.transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_APPROVAL)
        .reduce((a, b) -> b)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .filter(reason -> !reason.isBlank() && !reason.equals("approved"))
        .orElse("");
  }

  private static String chainSummaryFromArtifact(CreateChainEvent.ArtifactReady artifact) {
    if (!CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT.equals(artifact.artifactType())) {
      return null;
    }
    Object chainId = artifact.content().get("chainId");
    if (!(chainId instanceof String id) || id.isBlank()) {
      return "Chain is ready.";
    }
    Object name = artifact.content().get("chainName");
    String chainName = name instanceof String value ? value : "";
    Object status = artifact.content().get("status");
    String lifecycle = status instanceof String value ? value : "";
    return ChainMaterializedSummary.format(
        new ChainCatalogFacts(id, chainName, "", 0, 0, "", List.of(), List.of(), lifecycle));
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
    String stripped = prompt == null ? "" : prompt.strip();
    if (stripped.isBlank()) {
      return "";
    }
    if (stripped.startsWith("\n")) {
      return stripped;
    }
    return "\n\n" + stripped;
  }

  /**
   * True only for an explicit implement scenario. Free-form wording such as {@code Implement} does
   * not create a chain; that step is a command that names the approved plan.
   */
  private static boolean isImplementIntent(ScenarioType hint) {
    return hint == ScenarioType.IMPLEMENT_CHAIN;
  }

  private Optional<CreateChainPendingAction.Approve> visibleApproval(String conversationId) {
    return facade
        .snapshot(conversationId)
        .map(ExecutionSnapshot::pendingAction)
        .filter(CreateChainPendingAction.Approve.class::isInstance)
        .map(CreateChainPendingAction.Approve.class::cast);
  }

  /**
   * Reads a typed reply at an open gate and reports whether it approves the current candidate.
   *
   * <p>No pattern match decides this. The reply reaches a model that can only express an approval
   * by naming the artifact type, hash, and revision, and the facade refuses a binding that does not
   * match the open gate — so a qualified reply, a reply about later work, or a stale one leaves the
   * run where it is and travels on as input for the stage.
   */
  private boolean approvesCurrentCandidate(String conversationId, String text) {
    if (gateReplyAgent == null || text == null || text.isBlank()) {
      return false;
    }
    CreateChainPendingAction.Approve candidate = visibleApproval(conversationId).orElse(null);
    if (candidate == null) {
      return false;
    }
    AtomicReference<ApproveCandidateTool.Binding> named = new AtomicReference<>();
    try (AutoCloseable ignored = ApproveCandidateTool.capture(named)) {
      gateReplyAgent.interpretReply(
          "gate:" + conversationId,
          candidate.artifactType(),
          candidate.artifactHash(),
          candidate.revision(),
          text);
    } catch (Exception ex) {
      LOG.warnf(ex, "Gate reply agent failed; treating the reply as not an approval");
      return false;
    }
    ApproveCandidateTool.Binding binding = named.get();
    if (binding == null) {
      return false;
    }
    return facade
        .validateApprove(
            new ApproveCreateChainArtifactCommand(
                conversationId, binding.artifactType(), binding.artifactHash(), binding.revision()))
        .isEmpty();
  }
}
