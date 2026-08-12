package org.qubership.integration.platform.ai.chat.service;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
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

  private final CreateChainApplicationFacade facade;

  @Inject
  public ChatDecisionService(CreateChainApplicationFacade facade) {
    this.facade = Objects.requireNonNull(facade, "facade");
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
          default -> "Answered " + command.getAction();
        };
    String comment = command.getComment() == null ? "" : command.getComment().strip();
    return comment.isEmpty() ? marker : marker + "\n\n" + comment;
  }

  public Multi<ChatEvent> apply(String conversationId, ChatDecisionCommand command) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(command, "command");
    if (!ChatEvent.APPROVE_ACTION.equals(command.getAction())) {
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
    return facade.streamApprove(approval).onItem().transformToMultiAndConcatenate(this::toChatEvent);
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

  private Multi<ChatEvent> toChatEvent(CreateChainEvent event) {
    if (event instanceof CreateChainEvent.Message message) {
      return Multi.createFrom().item(ChatEvent.token(message.text()));
    }
    if (event instanceof CreateChainEvent.Progress progress) {
      return Multi.createFrom()
          .item(
              ChatEvent.step(
                  "stage:" + progress.label(), "pipeline", "running", progress.label(), null));
    }
    if (event instanceof CreateChainEvent.Waiting waiting) {
      return Multi.createFrom()
          .item(ChatEvent.decision(waiting.pendingAction(), revisionOf(waiting), ""));
    }
    if (event instanceof CreateChainEvent.Failed failed) {
      return Multi.createFrom().item(ChatEvent.error(failed.message()));
    }
    // ArtifactReady and Completed carry no chat text: the artifact prose already arrived as a
    // message, and the next gate arrives as its own decision.
    return Multi.createFrom().empty();
  }

  private static long revisionOf(CreateChainEvent.Waiting waiting) {
    return waiting.pendingAction() instanceof PendingAction.Approve approve
        ? approve.revision()
        : 0L;
  }
}
