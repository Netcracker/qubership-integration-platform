package org.qubership.integration.platform.ai.chat.service;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;
import org.qubership.integration.platform.ai.productpipeline.facade.ExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;

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
    return creation.isPresent() ? creation : importDecision(conversationId);
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
      return ChatEvent.actionsForGate(clarify.gateId());
    }
    return null;
  }

  private String storedQuestion(String conversationId, PendingAction pending) {
    if (pending instanceof PendingAction.Approve approve) {
      return approvalQuestions.find(conversationId, approve.artifactHash()).orElse("");
    }
    return "";
  }

  /**
   * Marker recorded in the transcript in place of the reader's click.
   *
   * <p>English and stable: the transcript is read by the language model, the button by a person, so
   * history reads the same whatever language the conversation is in.
   */
  public static String transcriptMarker(ChatDecisionCommand command) {
    String marker =
        switch (command.getAction() == null ? "" : command.getAction()) {
          case ChatEvent.APPROVE_ACTION ->
              "Approved " + command.getArtifactType() + " " + command.getArtifactHash();
          case ChatEvent.REQUEST_CHANGES_ACTION ->
              "Requested changes to " + command.getArtifactType();
          case ChatEvent.CREATE_ACTION -> "Create the chain in the catalog";
          case ChatEvent.APPLY_CHAIN_PATCH_ACTION -> "Apply the proposed change to the chain";
          case ChatEvent.IMPORT_ACTION -> ChatEvent.IMPORT_MARKER;
          default -> "Answered " + command.getAction();
        };
    String comment = command.getComment() == null ? "" : command.getComment().strip();
    return comment.isEmpty() ? marker : marker + "\n\n" + comment;
  }

  public Multi<ChatEvent> apply(String conversationId, ChatDecisionCommand command) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(command, "command");
    String action = command.getAction() == null ? "" : command.getAction();
    if (ChatEvent.CREATE_ACTION.equals(action)) {
      return createChain(conversationId, command.getArtifactHash(), command.getRevision());
    }
    if (!ChatEvent.APPROVE_ACTION.equals(action)
        && !ChatEvent.APPROVE_AND_CREATE_ACTION.equals(action)) {
      // Request-changes carries no command: the comment travels as an ordinary message instead.
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
      return Multi.createFrom().item(ChatEvent.error(failed.message()));
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
}
