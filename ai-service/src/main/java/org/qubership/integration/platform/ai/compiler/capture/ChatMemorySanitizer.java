package org.qubership.integration.platform.ai.compiler.capture;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
  private static final ObjectMapper HISTORY_JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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

  /** Starts a producer from explicit inputs without its previous tool exchange. */
  public void clear(String memoryId) {
    store.deleteMessages(memoryId);
  }

  /** Encodes rejected malformed calls for transport without changing raw responses or stored memory. */
  public static ChatRequest repairRejectedArguments(ChatRequest request) {
    List<ChatMessage> messages = request.messages();
    List<ChatMessage> repaired = null;
    for (int i = 0; i < messages.size(); i++) {
      if (!(messages.get(i) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) {
        continue;
      }
      List<ToolExecutionRequest> calls = new ArrayList<>(ai.toolExecutionRequests());
      boolean changed = false;
      for (int c = 0; c < calls.size(); c++) {
        ToolExecutionRequest call = calls.get(c);
        if (call.id() == null || call.id().isBlank() || call.arguments() == null
            || ai.toolExecutionRequests().stream().filter(other -> Objects.equals(other.id(), call.id())).count() != 1) {
          continue;
        }
        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (int j = i + 1; j < messages.size()
            && messages.get(j) instanceof ToolExecutionResultMessage result; j++) {
          if (Objects.equals(result.id(), call.id())) {
            results.add(result);
          }
        }
        if (results.size() != 1 || !Objects.equals(results.getFirst().toolName(), call.name())
            || !invalidJsonFeedback(results.getFirst().text())
            || !malformedJson(call.arguments())) {
          continue;
        }
        var envelope = HISTORY_JSON.createObjectNode();
        envelope.putObject("rejectedToolCall").put("status", "REJECTED").put("reason", "INVALID_JSON")
            .put("rawArguments", call.arguments());
        calls.set(c, call.toBuilder().arguments(envelope.toString()).build());
        changed = true;
      }
      if (changed) {
        if (repaired == null) repaired = new ArrayList<>(messages);
        repaired.set(i, ai.toBuilder().toolExecutionRequests(calls).build());
      }
    }
    return repaired == null ? request : request.toBuilder().messages(repaired).build();
  }

  private static boolean invalidJsonFeedback(String text) {
    if (text == null) return false;
    if (text.startsWith("INVALID_JSON ")) return true;
    try {
      var result = HISTORY_JSON.readTree(text);
      if (result == null) return false;
      if (result.isTextual()) return result.asText().startsWith("INVALID_JSON ");
      if (result.path("accepted").isBoolean() && !result.path("accepted").asBoolean()) {
        for (var issue : result.path("issues")) {
          if ("INVALID_JSON".equals(issue.path("code").asText())) return true;
        }
      }
    } catch (java.io.IOException ordinaryText) {
      // Other tool failures do not authorize changing historical arguments.
    }
    return false;
  }

  private static boolean malformedJson(String arguments) {
    try {
      var value = HISTORY_JSON.readTree(arguments);
      return value == null || value.isMissingNode();
    } catch (java.io.IOException malformed) {
      return true;
    }
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
