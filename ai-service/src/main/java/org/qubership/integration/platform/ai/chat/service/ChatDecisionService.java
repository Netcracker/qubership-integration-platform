package org.qubership.integration.platform.ai.chat.service;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.ExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;

/**
 * Runs a typed answer to a decision card against the same facade command the A2A transport uses.
 *
 * <p>No routing, no classification, no model call: the card carried the binding, and the facade
 * rejects a stale or wrong one with the validation it already performs. A refusal re-issues the
 * decision the run waits for now, so a click on a stale card is answered rather than ignored.
 */
@ApplicationScoped
public class ChatDecisionService {

  private static final Logger LOG = Logger.getLogger(ChatDecisionService.class);

  private final CreateChainApplicationFacade facade;
  private final ApprovalQuestionStore approvalQuestions;
  private final RequirementDraftStore draftStore;

  /** Null in unit tests, which build the service without an LLM; the English fallback stands in. */
  @Inject ApprovalPromptAgent promptAgent;

  @Inject UploadedSpecsApprovalHandler uploadedSpecsApprovalHandler;

  @Inject ProductPipelineArtifactStore artifactStore;

  @Inject ConversationService conversationService;

  @Inject
  public ChatDecisionService(
      CreateChainApplicationFacade facade,
      ApprovalQuestionStore approvalQuestions,
      RequirementDraftStore draftStore) {
    this.facade = Objects.requireNonNull(facade, "facade");
    this.approvalQuestions = Objects.requireNonNull(approvalQuestions, "approvalQuestions");
    this.draftStore = Objects.requireNonNull(draftStore, "draftStore");
  }

  /**
   * The gate a conversation is stopped at, or empty when it waits for nothing.
   *
   * <p>The server owns the open card: the browser re-fetches this on mount, on session switch, and
   * after a reconnect, so a gate answered in another tab or over A2A shows as answered, and an
   * aborted turn leaves nothing behind.
   */
  public Optional<ChatEvent.Decision> openDecision(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    Optional<ChatEvent.Decision> waiting =
        facade
            .snapshot(conversationId)
            .flatMap(
                snapshot ->
                    Optional.ofNullable(snapshot.pendingAction())
                        .map(
                            pending ->
                                (ChatEvent.Decision)
                                    ChatEvent.decision(
                                        pending,
                                        snapshot.revision(),
                                        storedQuestion(conversationId, pending),
                                        actionsFor(pending))));
    if (waiting.isPresent()) {
      return waiting;
    }
    Optional<ChatEvent.Decision> creation = creationDecision(conversationId);
    if (creation.isPresent()) {
      return creation;
    }
    Optional<ChatEvent.Decision> uploaded = uploadedSpecsDecision(conversationId);
    return uploaded.isPresent() ? uploaded : importDecision(conversationId);
  }

  /**
   * The uploaded-spec import as a card, while allowed attachments exist and no matching approval
   * record has been written yet.
   */
  private Optional<ChatEvent.Decision> uploadedSpecsDecision(String conversationId) {
    if (uploadedSpecsApprovalHandler == null
        || !uploadedSpecsApprovalHandler.needsApproval(conversationId)) {
      return Optional.empty();
    }
    if (artifactStore != null
        && artifactStore
            .findLatestApprovalRecord(
                runIdFor(conversationId),
                UploadedSpecsApprovalHandler.ARTIFACT_TYPE,
                uploadedSpecsApprovalHandler.attachmentHash(conversationId))
            .isPresent()) {
      return Optional.empty();
    }
    return Optional.of(uploadedSpecsApprovalHandler.createDecision(conversationId));
  }

  /**
   * The API Hub import as a card, while a candidate is selected and nothing is bound yet.
   *
   * <p>Replaces an instruction to type an English phrase, which no reply in another language ever
   * matched.
   */
  private Optional<ChatEvent.Decision> importDecision(String conversationId) {
    return draftStore
        .get(conversationId)
        .filter(RequirementDraft::hasPendingImport)
        .map(
            draft ->
                (ChatEvent.Decision)
                    ChatEvent.importDecision(
                        draft.apiHubCandidate().packageId(),
                        importQuestion(conversationId, draft)));
  }

  /**
   * The import question, authored in the language of the conversation and kept with the run.
   *
   * <p>Stored under the candidate the reader was shown, so a reload finds the same wording rather
   * than a freshly authored variant of it. English only when the model is absent or fails.
   */
  private String importQuestion(String conversationId, RequirementDraft draft) {
    String candidateId = draft.apiHubCandidate().packageId();
    Optional<String> stored = approvalQuestions.find(conversationId, candidateId);
    if (stored.isPresent()) {
      return stored.get();
    }
    String subject = importSubject(draft);
    String question = "Import the API Hub specification " + subject + " into the runtime catalog?";
    if (promptAgent != null) {
      try {
        String authored =
            promptAgent.askImportConfirmation(
                subject, facade.responseLocale(conversationId), draft.assembledText());
        if (authored != null && !authored.isBlank()) {
          question = authored.strip();
        }
      } catch (RuntimeException ex) {
        LOG.warnf(ex, "Import confirmation prompt LLM failed; using English fallback");
      }
    }
    approvalQuestions.save(conversationId, candidateId, question);
    return question;
  }

  private static String importSubject(RequirementDraft draft) {
    String name = draft.apiHubCandidate().packageName();
    return name == null || name.isBlank() ? draft.apiHubCandidate().packageId() : name;
  }

  /** The implementation gate as a card, when the run stands at it. */
  private Optional<ChatEvent.Decision> creationDecision(String conversationId) {
    return facade
        .pendingCreationHash(conversationId)
        .map(
            hash ->
                (ChatEvent.Decision)
                    ChatEvent.createChainDecision(
                        CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                        hash,
                        facade.snapshot(conversationId).map(ExecutionSnapshot::revision).orElse(0L),
                        approvalQuestions.find(conversationId, hash).orElse("")));
  }

  /**
   * Actions a gate offers. The plan gate keeps the happy path at one click by sending approval and
   * creation together; every other gate has nothing to create.
   */
  private static List<String> actionsFor(PendingAction pending) {
    if (pending instanceof PendingAction.Approve approve
        && CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN.equals(approve.artifactType())) {
      return List.of(ChatEvent.APPROVE_AND_CREATE_ACTION, ChatEvent.REQUEST_CHANGES_ACTION);
    }
    if (pending instanceof PendingAction.Clarify clarify) {
      return ChatEvent.actionsForClarify(clarify);
    }
    return null;
  }

  private String storedQuestion(String conversationId, PendingAction pending) {
    if (pending instanceof PendingAction.Approve approve) {
      return approvalQuestions.find(conversationId, approve.artifactHash()).orElse("");
    }
    if (pending instanceof PendingAction.Clarify clarify) {
      return clarify.reason();
    }
    return "";
  }

  public static String transcriptMarker(ChatDecisionCommand command) {
    return transcriptMarker(command, null);
  }

  /**
   * Marker recorded in the transcript in place of the reader's click.
   *
   * <p>English and stable: the transcript is read by the language model, the button by a person, so
   * history reads the same whatever language the conversation is in. Deploy, redeploy, and undeploy
   * name the pending domain when the card carried one.
   */
  public static String transcriptMarker(ChatDecisionCommand command, String domain) {
    String domainName = domain == null || domain.isBlank() ? "default" : domain;
    String marker =
        switch (command.getAction() == null ? "" : command.getAction()) {
          case ChatEvent.APPROVE_ACTION ->
              "Approved " + command.getArtifactType() + " " + command.getArtifactHash();
          case ChatEvent.REQUEST_CHANGES_ACTION ->
              "Requested changes to " + command.getArtifactType();
          case ChatEvent.CREATE_ACTION -> "Create the chain in the catalog";
          case ChatEvent.APPLY_CHAIN_PATCH_ACTION -> "Apply the proposed change to the chain";
          case ChatEvent.REDEPLOY_ACTION -> "Redeploy the chain on domain " + domainName;
          case ChatEvent.CANCEL_REDEPLOY_ACTION -> "Leave the live deployment unchanged";
          case ChatEvent.DEPLOY_ACTION -> "Deploy the chain on domain " + domainName;
          case ChatEvent.CANCEL_DEPLOY_ACTION -> "Do not deploy the chain";
          case ChatEvent.UNDEPLOY_ACTION -> "Undeploy the chain from domain " + domainName;
          case ChatEvent.CANCEL_UNDEPLOY_ACTION -> "Leave the live deployment in place";
          case ChatEvent.IMPORT_ACTION -> ChatEvent.IMPORT_MARKER;
          case ChatEvent.RETRY_CREATION_ACTION -> "Retry chain creation";
          case ChatEvent.EDIT_REQUIREMENTS_ACTION -> "Edit the requirements";
          case ChatEvent.REBUILD_PLAN_ACTION -> "Rebuild the plan";
          case PipelineGates.STOP_WITH_REPORT_ACTION -> "End the run and keep its report";
          case ChatEvent.SESSION_LOGGING_OFF_ACTION -> "Set session logging to Off";
          case ChatEvent.SESSION_LOGGING_ERROR_ACTION -> "Set session logging to Error";
          case ChatEvent.SESSION_LOGGING_INFO_ACTION -> "Set session logging to Info";
          case ChatEvent.SESSION_LOGGING_DEBUG_ACTION -> "Set session logging to Debug";
          default -> "Answered " + command.getAction();
        };
    String comment = command.getComment() == null ? "" : command.getComment().strip();
    return comment.isEmpty() ? marker : marker + "\n\n" + comment;
  }

  public Multi<ChatEvent> apply(String conversationId, ChatDecisionCommand command) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(command, "command");
    String action = command.getAction() == null ? "" : command.getAction();
    if (isPipelineInputAction(action) || isOpenClarifyChoice(conversationId, action)) {
      return continuePipelineInput(conversationId, command, action);
    }
    if (ChatEvent.CREATE_ACTION.equals(action)) {
      return createChain(conversationId, command.getArtifactHash(), command.getRevision());
    }
    if (UploadedSpecsApprovalHandler.ARTIFACT_TYPE.equals(command.getArtifactType())) {
      return handleUploadedSpecsApproval(conversationId, command, action);
    }
    if (ChatEvent.REQUEST_CHANGES_ACTION.equals(action)) {
      return requestChanges(conversationId, command);
    }
    if (!ChatEvent.APPROVE_ACTION.equals(action)
        && !ChatEvent.APPROVE_AND_CREATE_ACTION.equals(action)) {
      return Multi.createFrom().empty();
    }

    ApproveCreateChainArtifactCommand approval =
        new ApproveCreateChainArtifactCommand(
            conversationId,
            command.getArtifactType(),
            command.getArtifactHash(),
            command.getRevision());

    Optional<ApproveCreateChainOutcome> refusal = facade.validateApprove(approval);
    if (refusal.isPresent()) {
      return refused(conversationId, refusal.get());
    }
    Multi<ChatEvent> approved =
        facade
            .streamApproveOnly(approval)
            .onItem()
            .transformToMultiAndConcatenate(event -> toChatEvent(conversationId, event));
    if (!ChatEvent.APPROVE_AND_CREATE_ACTION.equals(action)) {
      return approved.onCompletion().switchTo(() -> openGateEvents(conversationId));
    }
    return approved.onCompletion().switchTo(() -> createAfterApproval(conversationId));
  }

  /**
   * Sends the change request into the run as typed input at the open approval card.
   *
   * <p>The UI does not post the comment as a chat message. An empty stream left the card answered
   * while the run was still waiting.
   */
  private Multi<ChatEvent> requestChanges(String conversationId, ChatDecisionCommand command) {
    String comment = command.getComment() == null ? "" : command.getComment().strip();
    String text = comment.isEmpty() ? transcriptMarker(command) : comment;
    return facade
        .continueWithInput(
            new ContinueCreateChainCommand(
                conversationId, text, UUID.randomUUID().toString(), InputOrigin.TRUSTED))
        .onItem()
        .transformToMultiAndConcatenate(event -> toChatEvent(conversationId, event))
        .onCompletion()
        .switchTo(() -> openGateEvents(conversationId))
        .onCompletion()
        .ifEmpty()
        .switchTo(
            () ->
                Multi.createFrom()
                    .item(ChatEvent.token(haltResumeProgress(ChatEvent.REQUEST_CHANGES_ACTION))));
  }

  /**
   * Routes a typed clarification-card answer straight to the durable run.
   *
   * <p>These actions are neither approvals nor model prompts. In particular, IDS {@code no} must
   * reach {@code design-input} exactly as {@code no}; sending it through the chat router used to
   * turn it into an ignored {@code "Answered no"} command.
   */
  private Multi<ChatEvent> continuePipelineInput(
      String conversationId, ChatDecisionCommand command, String action) {
    Optional<ChatEvent.Decision> open = openDecision(conversationId);
    if (open.isEmpty()
        || !open.get().actions().contains(action)
        || command.getRevision() != open.get().revision()) {
      return openGateEvents(conversationId);
    }
    String pipelineAction = toPipelineAction(action);
    return facade
        .continueWithInput(
            new ContinueCreateChainCommand(
                conversationId, pipelineAction, UUID.randomUUID().toString(), InputOrigin.TRUSTED))
        .onItem()
        .transformToMultiAndConcatenate(event -> toChatEvent(conversationId, event))
        .onCompletion()
        .switchTo(() -> openGateEvents(conversationId))
        .onCompletion()
        .ifEmpty()
        .switchTo(
            () -> Multi.createFrom().item(ChatEvent.token(haltResumeProgress(pipelineAction))));
  }

  /**
   * Visible progress when a halt-card resume commits RUNNING and the stage has not waited, failed,
   * or completed yet. Without this token the chat Multi is {@code meta} plus {@code done}.
   */
  static String haltResumeProgress(String action) {
    if (PipelineGates.REVISE_ACTION.equals(action)) {
      return "Revising the current stage.";
    }
    if (PipelineGates.RETRY_ACTION.equals(action)) {
      return "Retrying the current stage.";
    }
    return "Resuming the current stage.";
  }

  private static boolean isPipelineInputAction(String action) {
    return ChatEvent.IDS_PATH_CHOICE_ACTIONS.contains(action)
        || ChatEvent.MAPPING_GAP_ACTIONS.contains(action)
        || ChatEvent.RETRY_CREATION_ACTION.equals(action)
        || ChatEvent.EDIT_REQUIREMENTS_ACTION.equals(action)
        || ChatEvent.REBUILD_PLAN_ACTION.equals(action)
        || PipelineGates.STOP_WITH_REPORT_ACTION.equals(action)
        || PipelineGates.isHaltCardAction(action);
  }

  /** Maps a user-facing recovery action to the runtime command the run already understands. */
  private static String toPipelineAction(String action) {
    if (ChatEvent.RETRY_CREATION_ACTION.equals(action)) {
      return PipelineGates.RETRY_ACTION;
    }
    if (ChatEvent.EDIT_REQUIREMENTS_ACTION.equals(action)
        || ChatEvent.REBUILD_PLAN_ACTION.equals(action)) {
      return PipelineGates.REVISE_ACTION;
    }
    return action;
  }

  /**
   * Owner-choice buttons are stage ids, not the Retry/Revise tokens. Route them the same way when
   * they are on the open clarify card.
   */
  private boolean isOpenClarifyChoice(String conversationId, String action) {
    if (action == null || action.isBlank() || ChatEvent.IMPORT_ACTION.equals(action)) {
      return false;
    }
    return openDecision(conversationId)
        .filter(decision -> "clarify".equals(decision.kind()))
        .filter(decision -> decision.actions().contains(action))
        .isPresent();
  }

  /** Runs the creation leg of the combined action, if the run reached the gate at all. */
  private Multi<ChatEvent> createAfterApproval(String conversationId) {
    Optional<String> hash = facade.pendingCreationHash(conversationId);
    if (hash.isEmpty()) {
      return openGateEvents(conversationId);
    }
    long revision = facade.snapshot(conversationId).map(ExecutionSnapshot::revision).orElse(0L);
    return createChain(conversationId, hash.get(), revision);
  }

  /**
   * Writes the chain, then re-issues the gate if it is still open.
   *
   * <p>A failure after the approval succeeded leaves the run at the implementation gate, and the
   * card that comes back offers creation alone — a recoverable state rather than an ambiguous one.
   */
  private Multi<ChatEvent> createChain(String conversationId, String planHash, long revision) {
    return facade
        .streamCreateChain(conversationId, planHash, revision)
        .onItem()
        .transformToMultiAndConcatenate(event -> toChatEvent(conversationId, event))
        .onCompletion()
        .switchTo(() -> openGateEvents(conversationId));
  }

  /** The gate the run stands at now, as an event, or nothing when it waits for nothing. */
  private Multi<ChatEvent> openGateEvents(String conversationId) {
    return openDecision(conversationId)
        .map(decision -> Multi.createFrom().item((ChatEvent) decision))
        .orElseGet(() -> Multi.createFrom().empty());
  }

  /** Answers a refused command with the decision the run waits for now, or with the reason. */
  private Multi<ChatEvent> refused(String conversationId, ApproveCreateChainOutcome outcome) {
    Optional<ChatEvent> current =
        facade
            .snapshot(conversationId)
            .flatMap(
                snapshot ->
                    Optional.ofNullable(snapshot.pendingAction())
                        .map(pending -> reissue(snapshot, pending)));
    if (current.isPresent()) {
      return Multi.createFrom().item(current.get());
    }
    return Multi.createFrom().item(ChatEvent.token(refusalText(outcome)));
  }

  private static ChatEvent reissue(ExecutionSnapshot snapshot, PendingAction pending) {
    return ChatEvent.decision(pending, snapshot.revision(), "");
  }

  private static String refusalText(ApproveCreateChainOutcome outcome) {
    return switch (outcome) {
      case ApproveCreateChainOutcome.DuplicateApproval ignored -> "That was already approved.";
      case ApproveCreateChainOutcome.NonRecoverableFailure failure -> failure.reason();
      default -> "The question moved on. There is nothing to approve right now.";
    };
  }

  private Multi<ChatEvent> toChatEvent(String conversationId, CreateChainEvent event) {
    if (event instanceof CreateChainEvent.Message message) {
      return Multi.createFrom().item(ChatEvent.token(message.text()));
    }
    if (event instanceof CreateChainEvent.Progress progress) {
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
      return Multi.createFrom()
          .item(
              ChatEvent.decision(
                  waiting.pendingAction(),
                  revisionOf(conversationId, waiting),
                  "",
                  actionsFor(waiting.pendingAction())));
    }
    if (event instanceof CreateChainEvent.Failed failed) {
      return Multi.createFrom().item(ChatEvent.token(failed.message()));
    }
    // ArtifactReady and Completed carry no chat text: the artifact prose already arrived as a
    // message, and the next gate arrives as its own decision.
    return Multi.createFrom().empty();
  }

  /** A clarification carries no revision of its own, so the run's is what identifies the card. */
  private long revisionOf(String conversationId, CreateChainEvent.Waiting waiting) {
    if (waiting.pendingAction() instanceof PendingAction.Approve approve) {
      return approve.revision();
    }
    return facade.snapshot(conversationId).map(ExecutionSnapshot::revision).orElse(0L);
  }

  /**
   * Handles the chat-layer approval gate for uploaded API specifications.
   *
   * <p>On approve, writes an APPROVAL_RECORD artifact into the pipeline context and starts the
   * CREATE run with the original user message. Other actions are treated as clarification requests.
   */
  private Multi<ChatEvent> handleUploadedSpecsApproval(
      String conversationId, ChatDecisionCommand command, String action) {
    if (artifactStore == null || uploadedSpecsApprovalHandler == null) {
      LOG.warnf(
          "Uploaded-specs approval is not wired for conversationId=%s; ignoring decision",
          conversationId);
      return Multi.createFrom().empty();
    }
    if (!ChatEvent.APPROVE_ACTION.equals(action)) {
      return Multi.createFrom().empty();
    }
    ChatEvent.Decision decision =
        new ChatEvent.Decision(
            UploadedSpecsApprovalHandler.ARTIFACT_TYPE + ":" + command.getArtifactHash(),
            ChatEvent.APPROVE_ACTION,
            "",
            command.getArtifactType(),
            command.getArtifactHash(),
            command.getRevision(),
            null,
            List.of(),
            List.of("approve", "clarify"));
    uploadedSpecsApprovalHandler.appendApprovalRecord(
        runIdFor(conversationId), conversationId, decision, "user", artifactStore);
    LOG.infof(
        "Approved uploaded-specs import conversationId=%s runId=%s",
        conversationId, runIdFor(conversationId));
    return facade
        .start(new StartCreateChainCommand(conversationId, originalUserText(conversationId)))
        .onItem()
        .transformToMultiAndConcatenate(event -> toChatEvent(conversationId, event))
        .onCompletion()
        .switchTo(() -> openGateEvents(conversationId));
  }

  /** Returns the last user-authored message, skipping the decision marker if present. */
  private String originalUserText(String conversationId) {
    if (conversationService == null) {
      return "";
    }
    List<ConversationMessage> messages = conversationService.getMessages(conversationId);
    for (int i = messages.size() - 1; i >= 0; i--) {
      ConversationMessage message = messages.get(i);
      if (message.role() != ConversationMessage.Role.USER) {
        continue;
      }
      String text = message.content() == null ? "" : message.content();
      if (text.startsWith("Approved ") || text.startsWith("Answered ")) {
        continue;
      }
      return text;
    }
    return "";
  }

  private static String runIdFor(String conversationId) {
    return conversationId
        + "-"
        + CreateRunSelectionService.CREATE_PROFILE_ID
        + "-"
        + CreateRunSelectionService.CREATE_PROFILE_VERSION;
  }
}
