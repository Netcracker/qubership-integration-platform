package org.qubership.integration.platform.ai.chat;

/**
 * One item a {@code ScenarioHandler} emits during a chat turn. Handlers produce typed events only;
 * {@code ChatExecutionService} owns all SSE framing and escaping so the wire format lives in one place.
 */
public sealed interface ChatEvent {

  /** Stream metadata emitted once at the start of an SSE turn. */
  record Meta(String conversationId) implements ChatEvent {}

  /** Streamed assistant content shown to the user. */
  record Token(String text) implements ChatEvent {}

  /** Activity step progress (rendered as {@code event: step}, replace-by-id). */
  record Step(String id, String kind, String status, String label, String parentId)
      implements ChatEvent {}

  /** Human-in-the-loop checkpoint awaiting a user answer (rendered as {@code event: hitl}). */
  record Hitl(String checkpointId, String question) implements ChatEvent {}

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

  static ChatEvent error(String message) {
    return new Error(message);
  }
}
