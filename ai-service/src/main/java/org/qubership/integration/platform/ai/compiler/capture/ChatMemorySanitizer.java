package org.qubership.integration.platform.ai.compiler.capture;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Repairs the shapes a conversation's shared chat memory can be left in that OpenAI refuses on the
 * next request, whichever turn produced them.
 *
 * <p>When tool-argument deserialization fails ({@code ToolArgumentsException}), quarkus-langchain4j
 * rethrows before the tool runs, so the assistant message carrying the {@code tool_call} stays in
 * memory with no matching tool-result message. Any later agent turn on the same {@code
 * conversationId} then sends OpenAI an assistant {@code tool_calls} message not followed by tool
 * results, which is rejected with {@code invalid_request_error}. This appends a synthetic error
 * result for each dangling {@code tool_call} so the next turn is well formed.
 *
 * <p>An empty assistant completion leaves the other refused shape behind; see {@link
 * #isEmptyAssistantTurn}. Both are repaired in the same pass, because both cost the conversation
 * every turn that follows rather than only the turn that produced them.
 */
@ApplicationScoped
public class ChatMemorySanitizer {

  private static final Logger LOG = Logger.getLogger(ChatMemorySanitizer.class);

  /** Default filler for true tool-argument parse failures. */
  static final String DANGLING_RESULT_TEXT =
      "ERROR: tool arguments could not be parsed; the call was skipped.";

  /**
   * Fallback text for docs/tests when a validation CVE path has no non-blank summary. Production
   * prefers the captured validation summary via {@link #repairDanglingToolCalls(String, String)}.
   */
  static final String VALIDATION_DANGLING_RESULT_TEXT =
      "ERROR: capture validation failed; call the capture tool again with corrected fields.";

  private final ChatMemoryStore store;

  @Inject
  public ChatMemorySanitizer(ChatMemoryStore store) {
    this.store = store;
  }

  /**
   * Appends a synthetic parse-failure result for every {@code tool_call} in the memory that has no
   * matching tool-result message. Returns the number of results inserted.
   */
  public int repairDanglingToolCalls(String conversationId) {
    return repairDanglingToolCalls(conversationId, DANGLING_RESULT_TEXT);
  }

  /**
   * Appends a synthetic result with {@code danglingResultText} for every unanswered {@code
   * tool_call}. Use this after a validation CVE so the LLM sees the real validation summary instead
   * of a false parse-failure message.
   */
  public int repairDanglingToolCalls(String conversationId, String danglingResultText) {
    if (conversationId == null || conversationId.isBlank()) {
      return 0;
    }
    String resultText =
        danglingResultText == null || danglingResultText.isBlank()
            ? DANGLING_RESULT_TEXT
            : danglingResultText;
    List<ChatMessage> messages = store.getMessages(conversationId);
    if (messages == null || messages.isEmpty()) {
      return 0;
    }

    Set<String> answered = new HashSet<>();
    for (ChatMessage message : messages) {
      if (message instanceof ToolExecutionResultMessage result) {
        answered.add(result.id());
      }
    }

    List<ChatMessage> repaired = new ArrayList<>(messages.size());
    int inserted = 0;
    int dropped = 0;
    for (ChatMessage message : messages) {
      if (isEmptyAssistantTurn(message)) {
        dropped++;
        continue;
      }
      repaired.add(message);
      if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
        for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
          if (answered.add(request.id())) {
            // add() returned true: this id had no result anywhere, so it is dangling. Insert the
            // synthetic result right after the assistant message that requested it.
            repaired.add(ToolExecutionResultMessage.from(request, resultText));
            inserted++;
          }
        }
      }
    }

    if (inserted > 0 || dropped > 0) {
      store.updateMessages(conversationId, repaired);
      LOG.warnf(
          "Repaired chat memory conversationId=%s danglingResults=%d emptyAssistantTurns=%d",
          conversationId, inserted, dropped);
    }
    return inserted;
  }

  /**
   * An assistant turn that says nothing and asks for nothing.
   *
   * <p>A model that answers a tool result with neither text nor another tool call leaves an {@link
   * AiMessage} with null text and no tool calls in memory. OpenAI serializes that as {@code
   * content: null} on a message carrying no {@code tool_calls} to justify it, and refuses every
   * later request on the conversation for it -- including the repair retry that would have
   * corrected whatever the tool rejected in the first place. One empty completion therefore costs
   * the whole run rather than one turn.
   *
   * <p>Null text alongside tool calls is the ordinary shape of a tool-calling turn and is kept:
   * OpenAI accepts it, and dropping it would strand the tool results that answer it.
   */
  private static boolean isEmptyAssistantTurn(ChatMessage message) {
    if (!(message instanceof AiMessage ai) || ai.hasToolExecutionRequests()) {
      return false;
    }
    return ai.text() == null || ai.text().isBlank();
  }
}
