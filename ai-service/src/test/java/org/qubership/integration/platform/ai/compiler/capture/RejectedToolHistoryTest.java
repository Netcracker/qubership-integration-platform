package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RejectedToolHistoryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REFUSAL = "INVALID_JSON /: Send one valid JSON argument object. Full native refusal.";

  @ParameterizedTest
  @ValueSource(strings = {"", "{", "{\"x\":\"quoted \\\"value\\\"\"} trailing", "{} {}"})
  void strictMalformedArgumentsAreEncodedAsRejectedWhileTheCallerAndResultsStayImmutable(String raw) throws Exception {
    var bad = call("bad", raw);
    var good = call("good", " { \"x\" : 1 } ");
    var ai = AiMessage.builder().text("two calls").thinking("keep reasoning")
        .attributes(Map.of("native", "metadata")).toolExecutionRequests(List.of(bad, good)).build();
    var refusal = ToolExecutionResultMessage.from(bad, REFUSAL);
    var successful = ToolExecutionResultMessage.from(good, "complete unrelated result");
    var request = ChatRequest.builder().messages(UserMessage.from("source"), ai, refusal, successful).build();

    var repaired = ChatMemorySanitizer.repairRejectedArguments(request);

    var changed = (AiMessage) repaired.messages().get(1);
    var envelope = JSON.readTree(changed.toolExecutionRequests().getFirst().arguments()).path("rejectedToolCall");
    assertEquals("REJECTED", envelope.path("status").asText());
    assertEquals("INVALID_JSON", envelope.path("reason").asText());
    assertEquals(raw, envelope.path("rawArguments").asText());
    assertEquals(bad.id(), changed.toolExecutionRequests().getFirst().id());
    assertEquals(bad.name(), changed.toolExecutionRequests().getFirst().name());
    assertSame(good, changed.toolExecutionRequests().get(1));
    assertEquals(ai.text(), changed.text());
    assertEquals(ai.thinking(), changed.thinking());
    assertEquals(ai.attributes(), changed.attributes());
    assertSame(refusal, repaired.messages().get(2));
    assertSame(successful, repaired.messages().get(3));
    assertSame(request.parameters(), repaired.parameters());
    assertEquals(raw, ai.toolExecutionRequests().getFirst().arguments());
    assertSame(repaired, ChatMemorySanitizer.repairRejectedArguments(repaired));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", " { \"x\" : 1 } ", "[]", "null", "false", "\"text\""})
  void everySyntacticallyValidJsonValueStaysByteIdenticalEvenWithAnInvalidJsonResult(String raw) {
    var call = call("valid", raw);
    var request = ChatRequest.builder().messages(AiMessage.from(call), ToolExecutionResultMessage.from(call, REFUSAL)).build();
    assertSame(request, ChatMemorySanitizer.repairRejectedArguments(request));
  }

  @Test
  void malformedHistoryNeedsAnUnambiguousNativeRefusalInItsOwnToolGroup() {
    var bad = call("bad", "{");
    var result = ToolExecutionResultMessage.from(bad, REFUSAL);
    var cases = List.of(
        List.<ChatMessage>of(AiMessage.from(bad)),
        List.<ChatMessage>of(AiMessage.from(bad), ToolExecutionResultMessage.from(bad, "Business validation failed")),
        List.<ChatMessage>of(AiMessage.from(bad), ToolExecutionResultMessage.from("other", bad.name(), REFUSAL)),
        List.<ChatMessage>of(AiMessage.from(bad), ToolExecutionResultMessage.from(bad.id(), "differentTool", REFUSAL)),
        List.<ChatMessage>of(AiMessage.from(bad), result, result),
        List.<ChatMessage>of(AiMessage.from(bad), result,
            ToolExecutionResultMessage.from(bad.id(), "differentTool", REFUSAL)),
        List.<ChatMessage>of(AiMessage.from(bad, bad), result),
        List.<ChatMessage>of(AiMessage.from(bad,
            ToolExecutionRequest.builder().id(bad.id()).name("differentTool").arguments("{}").build()), result),
        List.<ChatMessage>of(AiMessage.from(bad), UserMessage.from("next source"), result),
        List.<ChatMessage>of(AiMessage.from(bad), AiMessage.from(call("other", "{}")), result));
    for (var messages : cases) {
      var request = ChatRequest.builder().messages(messages).build();
      assertSame(request, ChatMemorySanitizer.repairRejectedArguments(request), messages.toString());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"INVALID_JSON /: native scalar result\"",
      "{\"accepted\":false,\"issues\":[{\"code\":\"INVALID_JSON\",\"message\":\"full native details\"}]}"})
  void nativeScalarAndStructuredRefusalsKeepTheirCompletePayload(String refusal) {
    var bad = call("bad", "{");
    var result = ToolExecutionResultMessage.from(bad, refusal);
    var request = ChatRequest.builder().messages(AiMessage.from(bad), result).build();
    var repaired = ChatMemorySanitizer.repairRejectedArguments(request);
    assertNotSame(request, repaired);
    assertSame(result, repaired.messages().getLast());
  }

  private static ToolExecutionRequest call(String id, String arguments) {
    return ToolExecutionRequest.builder().id(id).name("captureGraphPatch").arguments(arguments).build();
  }
}
