package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.configuration.AppConfig;

class ProviderBoundaryEvidenceTest {
  @TempDir Path directory;

  @Test void disabledRecordingCreatesNoFilesAndExporterFailureDoesNotChangeTheRequest() throws Exception {
    var request = request("author source");
    assertNull(new ProviderBoundaryEvidence(null).request(request, "run"));
    enable(directory);
    Files.writeString(directory.resolve("provider-boundary"), "occupied");
    assertDoesNotThrow(() -> new ProviderBoundaryEvidence(directory).request(request, "run"));
    assertEquals("author source", ((UserMessage) request.messages().getFirst()).singleText());
  }

  @Test void synchronousAndStreamingDelegationRecordThePreparedRequest() throws Exception {
    for (boolean streaming : new boolean[] {false, true}) {
      Path output = directory.resolve(String.valueOf(streaming));
      enable(output);
      AppConfig config = mock(AppConfig.class, RETURNS_DEEP_STUBS);
      when(config.e2e().recoveryControlDirectory()).thenReturn(Optional.of(output.toString()));
      var budget = new LlmRequestBudget(config);
      var prepared = budget.prepare(request("source Bearer secretvalue and sk-1234567890abcdefghijklmnop password=private-value"));
      var response = ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("accepted")).build();
      if (streaming) {
        var upstream = mock(dev.langchain4j.model.chat.StreamingChatModel.class);
        doAnswer(call -> {
          assertSame(prepared, call.getArgument(0));
          ((dev.langchain4j.model.chat.response.StreamingChatResponseHandler) call.getArgument(1))
              .onCompleteResponse(response);
          return null;
        }).when(upstream).chat(any(ChatRequest.class), any(dev.langchain4j.model.chat.response.StreamingChatResponseHandler.class));
        new RateLimitAwareStreamingChatModel(upstream, new RateLimitErrorClassifier(),
            new RateLimitWaitPolicy(), seconds -> {}, false, 0, event -> {})
            .withRunBudget(null, "run", budget).chat(prepared,
                mock(dev.langchain4j.model.chat.response.StreamingChatResponseHandler.class));
      } else {
        var upstream = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(upstream.chat(prepared)).thenReturn(response);
        assertSame(response, new RateLimitAwareChatModel(upstream, new RateLimitErrorClassifier(),
            new RateLimitWaitPolicy(), seconds -> {}, false, 0, event -> {})
            .withRunBudget(null, "run", budget).chat(prepared));
      }
      try (var files = Files.list(output.resolve("provider-boundary"))) {
        var records = files.toList();
        assertEquals(2, records.size());
        var record = new ObjectMapper().readTree(records.stream().filter(path -> path.toString().endsWith("-request.json"))
            .findFirst().orElseThrow().toFile());
        assertTrue(record.path("redacted").asBoolean());
        assertNotEquals(record.path("originalRequestHash"), record.path("redactedRequestHash"));
        String saved = record.toString();
        assertFalse(saved.contains("secretvalue"));
        assertFalse(saved.contains("sk-123456"));
        assertFalse(saved.contains("private-value"));
        assertTrue(saved.contains("source"));
        assertEquals(512, record.path("request").path("parameters").path("maxOutputTokens").asInt());
      }
    }
  }

  @Test void aForeignRunIsNotRecorded() throws Exception {
    enable(directory);
    assertNull(new ProviderBoundaryEvidence(directory).request(request("foreign author"), "foreign-run"));
    assertFalse(Files.exists(directory.resolve("provider-boundary")));
  }

  private static void enable(Path output) throws Exception {
    Files.createDirectories(output);
    Files.writeString(output.resolve("plan.json"), "{\"conversationId\":\"owned\"}");
    Files.writeString(output.resolve("provider-context.json"),
        "{\"runId\":\"run\",\"conversationId\":\"owned\",\"stageId\":\"stage\",\"attemptId\":\"attempt\"}");
  }

  private static ChatRequest request(String text) {
    return ChatRequest.builder().messages(UserMessage.from(text))
        .parameters(DefaultChatRequestParameters.builder().maxOutputTokens(512).build()).build();
  }
}
