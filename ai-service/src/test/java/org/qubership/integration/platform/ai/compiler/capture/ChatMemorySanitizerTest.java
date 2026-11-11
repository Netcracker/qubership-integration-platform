package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMemorySanitizerTest {

  private static ToolExecutionRequest request(String id) {
    return ToolExecutionRequest.builder().id(id).name("captureGraphPatch").arguments("{}").build();
  }

  @Test
  void appendsResultForDanglingToolCall() {
    InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    store.updateMessages(
        "conv",
        List.of(
            UserMessage.from("go"),
            AiMessage.from(request("call-1")))); // tool_call with no following result
    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(store);

    int inserted = sanitizer.repairDanglingToolCalls("conv");

    assertEquals(1, inserted);
    List<ChatMessage> messages = store.getMessages("conv");
    assertEquals(3, messages.size());
    ToolExecutionResultMessage result =
        assertInstanceOf(ToolExecutionResultMessage.class, messages.get(2));
    assertEquals("call-1", result.id());
    assertEquals(ChatMemorySanitizer.DANGLING_RESULT_TEXT, result.text());
  }

  /**
   * The shape that cost a chain edit its repair retry. The model answered a rejected patch with an
   * empty completion, and OpenAI then refused every request on that conversation for
   * {@code messages.[4].content} being null -- before the retry could correct the patch.
   */
  @Test
  void dropsAnAssistantTurnThatSaysNothingAndAsksForNothing() {
    InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    store.updateMessages(
        "conv",
        List.of(
            UserMessage.from("go"),
            AiMessage.from(request("call-1")),
            ToolExecutionResultMessage.from(request("call-1"), "ownership violation"),
            AiMessage.builder().build()));
    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(store);

    sanitizer.repairDanglingToolCalls("conv");

    List<ChatMessage> messages = store.getMessages("conv");
    assertEquals(3, messages.size());
    assertInstanceOf(ToolExecutionResultMessage.class, messages.get(2));
  }

  /** Null text alongside tool calls is the ordinary tool-calling turn, and OpenAI accepts it. */
  @Test
  void keepsAToolCallingTurnWhoseTextIsNull() {
    InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    store.updateMessages(
        "conv",
        List.of(
            UserMessage.from("go"),
            AiMessage.from(request("call-1")),
            ToolExecutionResultMessage.from(request("call-1"), "ok")));
    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(store);

    sanitizer.repairDanglingToolCalls("conv");

    assertEquals(3, store.getMessages("conv").size());
    assertInstanceOf(AiMessage.class, store.getMessages("conv").get(1));
  }

  @Test
  void defaultRepairInsertsParseFailureText() {
    InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    store.updateMessages(
        "conv",
        List.of(UserMessage.from("go"), AiMessage.from(request("call-parse"))));
    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(store);

    int inserted = sanitizer.repairDanglingToolCalls("conv");

    assertEquals(1, inserted);
    ToolExecutionResultMessage result =
        assertInstanceOf(ToolExecutionResultMessage.class, store.getMessages("conv").get(2));
    assertEquals(
        "ERROR: tool arguments could not be parsed; the call was skipped.", result.text());
  }

  @Test
  void overloadInsertsCustomDanglingResultText() {
    InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    store.updateMessages(
        "conv",
        List.of(UserMessage.from("go"), AiMessage.from(request("call-validation"))));
    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(store);
    String custom = ChatMemorySanitizer.VALIDATION_DANGLING_RESULT_TEXT;

    int inserted = sanitizer.repairDanglingToolCalls("conv", custom);

    assertEquals(1, inserted);
    ToolExecutionResultMessage result =
        assertInstanceOf(ToolExecutionResultMessage.class, store.getMessages("conv").get(2));
    assertEquals(custom, result.text());
  }

  @Test
  void leavesAnsweredToolCallsUntouched() {
    InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    List<ChatMessage> original =
        List.of(
            UserMessage.from("go"),
            AiMessage.from(request("call-1")),
            ToolExecutionResultMessage.from(request("call-1"), "done"));
    store.updateMessages("conv", original);
    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(store);

    int inserted = sanitizer.repairDanglingToolCalls("conv");

    assertEquals(0, inserted);
    assertEquals(3, store.getMessages("conv").size());
  }

  @Test
  void returnsZeroForEmptyMemory() {
    ChatMemorySanitizer sanitizer = new ChatMemorySanitizer(new InMemoryChatMemoryStore());
    assertEquals(0, sanitizer.repairDanglingToolCalls("missing"));
  }
}
