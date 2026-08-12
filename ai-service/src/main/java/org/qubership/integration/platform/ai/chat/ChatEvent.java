package org.qubership.integration.platform.ai.chat;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;

/**
 * One item a {@code ScenarioHandler} emits during a chat turn. Handlers produce typed events only;
 * {@code ChatExecutionService} owns all SSE framing and escaping so the wire format lives in one place.
 */
public sealed interface ChatEvent {

  /** Action names a decision card offers, localized in the interface, never on the wire. */
  String APPROVE_ACTION = "approve";

  String REQUEST_CHANGES_ACTION = "request-changes";

  /** Stream metadata emitted once at the start of an SSE turn. */
  record Meta(String conversationId) implements ChatEvent {}

  /** Streamed assistant content shown to the user. */
  record Token(String text) implements ChatEvent {}

  /** Activity step progress (rendered as {@code event: step}, replace-by-id). */
  record Step(String id, String kind, String status, String label, String parentId)
      implements ChatEvent {}

  /** Human-in-the-loop checkpoint awaiting a user answer (rendered as {@code event: hitl}). */
  record Hitl(String checkpointId, String question) implements ChatEvent {}

  /**
   * A gate the run stopped at, rendered as a card in the transcript (rendered as {@code event:
   * decision}).
   *
   * @param id identity of the gate; the same gate re-emitted after a reconnect carries the same id
   * @param kind {@code approve} or {@code clarify}
   * @param question server-authored text in the language of the conversation
   * @param actions what the gate accepts, empty when the answer is free text
   */
  record Decision(
      String id,
      String kind,
      String question,
      String artifactType,
      String artifactHash,
      long revision,
      String reason,
      List<String> missingEvidence,
      List<String> actions)
      implements ChatEvent {

    public Decision {
      missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  /** Terminal error surfaced to the user (rendered as {@code event: error}). */
  record Error(String message) implements ChatEvent {}

  static ChatEvent meta(String conversationId) {
    return new Meta(conversationId);
  }

  static ChatEvent token(String text) {
    return new Token(text);
  }

  static ChatEvent step(String id, String kind, String status, String label, String parentId) {
    return new Step(id, kind, status, label, parentId);
  }

  /** Convenience for skill steps when label equals the skill id. */
  static ChatEvent skillStep(String skillId, String status) {
    return step("skill:" + skillId, "skill", status, skillId, null);
  }

  static ChatEvent hitl(String checkpointId, String question) {
    return new Hitl(checkpointId, question);
  }

  /**
   * Derives the card from what a run waits for.
   *
   * <p>The only mapping from a pipeline wait to a chat event, so a gate cannot reach the reader as
   * prose. {@code question} wins over the prompt carried by the wait, which is blank when the run
   * is resumed rather than freshly stopped.
   */
  static ChatEvent decision(PendingAction pending, long revision, String question) {
    Objects.requireNonNull(pending, "pending");
    String text = question == null ? "" : question.strip();
    if (pending instanceof PendingAction.Approve approve) {
      return new Decision(
          "approve:" + approve.artifactHash(),
          approve.action(),
          text.isBlank() ? approve.prompt() : text,
          approve.artifactType(),
          approve.artifactHash(),
          approve.revision(),
          null,
          List.of(),
          List.of(APPROVE_ACTION, REQUEST_CHANGES_ACTION));
    }
    if (pending instanceof PendingAction.Clarify clarify) {
      return new Decision(
          "clarify:" + revision,
          clarify.action(),
          text,
          null,
          null,
          revision,
          clarify.reason(),
          clarify.missingEvidence(),
          List.of());
    }
    throw new IllegalArgumentException("unsupported pending action: " + pending.action());
  }

  static ChatEvent error(String message) {
    return new Error(message);
  }
}
