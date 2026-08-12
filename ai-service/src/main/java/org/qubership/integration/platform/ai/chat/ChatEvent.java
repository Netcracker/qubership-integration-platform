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

  /** Writes the chain into the catalog: the one irreversible step, never a model's to take. */
  String CREATE_ACTION = "create-chain";

  /** Approves the plan and creates the chain, each validated against its own binding. */
  String APPROVE_AND_CREATE_ACTION = "approve-and-create";

  /** Imports the selected API Hub specification into the runtime catalog. */
  String IMPORT_ACTION = "import-specification";

  /**
   * What the transcript records when the reader answers the import card.
   *
   * <p>Read by the import stage as the confirmation itself, so the stage checks a marker this
   * service wrote rather than guessing at wording a reader chose.
   */
  String IMPORT_MARKER = "Import the API Hub specification";

  /** Stream metadata emitted once at the start of an SSE turn. */
  record Meta(String conversationId) implements ChatEvent {}

  /** Streamed assistant content shown to the user. */
  record Token(String text) implements ChatEvent {}

  /** Activity step progress (rendered as {@code event: step}, replace-by-id). */
  record Step(String id, String kind, String status, String label, String parentId)
      implements ChatEvent {}

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

  /**
   * Derives the card from what a run waits for.
   *
   * <p>The only mapping from a pipeline wait to a chat event, so a gate cannot reach the reader as
   * prose. {@code question} wins over the prompt carried by the wait, which is blank when the run
   * is resumed rather than freshly stopped.
   */
  static ChatEvent decision(PendingAction pending, long revision, String question) {
    return decision(pending, revision, question, null);
  }

  /**
   * Same, with the actions the caller knows this gate accepts.
   *
   * <p>The plan gate offers creation, the others do not, and only the pipeline knows which is
   * which — so the list is passed in rather than guessed from the artifact type here.
   */
  static ChatEvent decision(
      PendingAction pending, long revision, String question, List<String> actions) {
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
          actions == null ? List.of(APPROVE_ACTION, REQUEST_CHANGES_ACTION) : actions);
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
          actions == null ? List.of() : actions);
    }
    throw new IllegalArgumentException("unsupported pending action: " + pending.action());
  }

  /**
   * The implementation gate, offered as its own decision.
   *
   * <p>Creating the chain is a command distinct from approving the plan, so it carries its own id
   * and its own binding: a card left over from an earlier plan cannot create anything.
   */
  static ChatEvent createChainDecision(
      String artifactType, String planHash, long revision, String question) {
    Objects.requireNonNull(artifactType, "artifactType");
    Objects.requireNonNull(planHash, "planHash");
    return new Decision(
        "create:" + planHash,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        artifactType,
        planHash,
        revision,
        null,
        List.of(),
        List.of(CREATE_ACTION));
  }

  /**
   * The API Hub import, offered as its own decision.
   *
   * <p>A real transition backs it — the specification lands in the runtime catalog — so it is a
   * decision rather than prose. The candidate the reader was shown identifies the card.
   */
  static ChatEvent importDecision(String candidateId, String question) {
    Objects.requireNonNull(candidateId, "candidateId");
    return new Decision(
        "import:" + candidateId,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        null,
        null,
        0L,
        null,
        List.of(),
        List.of(IMPORT_ACTION));
  }

  static ChatEvent error(String message) {
    return new Error(message);
  }
}
