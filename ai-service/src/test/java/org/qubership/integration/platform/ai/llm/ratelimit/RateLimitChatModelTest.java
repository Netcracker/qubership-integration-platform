package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.configuration.AppConfig;

class RateLimitChatModelTest {
  @Test
  void authorHistoryLookalikesAndCopiedOldProjectionsAreNotPruned() {
    var history = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    history.put("readOnly", true).putArray("attempts").addObject().put("proposal", "author value");
    String lookalike = "\n<repair-history>" + history + "</repair-history>\nContext: "
        + "{\"repairHistory\":" + history + ",\"authorInputs\":[\"NONE\"]}";
    assertEquals(lookalike, org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.omit(lookalike));
    String old = org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.block(history.toString());
    org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.bind("prior request" + old);
    history.withArray("attempts").addObject().put("proposal", "fresh generated value");
    String fresh = org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.block(history.toString());
    String source = lookalike + "\nQuoted old diagnostic:" + old;
    String reduced = org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.omit(source + fresh);
    assertTrue(reduced.startsWith(source));
    assertFalse(reduced.substring(source.length()).contains("fresh generated value"));
  }

  @Test
  void configuredRequestBudgetOmitsOptionalHistoryAndKeepsMandatoryContext() throws Exception {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().contextTokens()).thenReturn(2000);
    when(config.llm().contextHeadroomTokens()).thenReturn(128);
    var history = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    history.put("readOnly", true).put("partial", false).putArray("attempts")
        .addObject().put("proposal", "rejected words ".repeat(4000));
    String source = "Keep confirmed NONE, $.Subject, and POST /orders-v2.";
    var request = ChatRequest.builder().messages(UserMessage.from(source
        + org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.block(history.toString())))
        .parameters(DefaultChatRequestParameters.builder().maxOutputTokens(512).build()).build();
    var prepared = new LlmRequestBudget(config).prepare(request);
    String text = ((UserMessage) prepared.messages().getFirst()).singleText();
    assertTrue(text.startsWith(source));
    assertTrue(text.contains("\"partial\":true"));
    assertFalse(text.contains("rejected words"));
    assertTrue(((UserMessage) request.messages().getFirst()).singleText().contains("rejected words"));
    assertThrows(LlmRequestBudget.Overflow.class, () -> new LlmRequestBudget(config).prepare(
        request.toBuilder().messages(UserMessage.from(source.repeat(4000))).build()));
  }

  @Test
  void providerOverflowRetriesOnlyAChangedPacketAndKeepsDistinctReadsAndFailures() {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().transientRetry().backoffSeconds()).thenReturn("2,5,10");
    var messages = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
    messages.add(UserMessage.from("Do not log bodies. Preserve $.Subject and the accepted NONE decision."));
    var tool = "listCatalogOperations";
    for (var id : java.util.List.of("page-1", "failure", "page-2", "page-1-again")) {
      var call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id(id).name(tool)
          .arguments("page-2".equals(id) ? "{\"page\":2}" : "{\"page\":1}").build();
      messages.add(dev.langchain4j.data.message.AiMessage.from(call));
      messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(call,
          "failure".equals(id) ? "{\"ok\":false,\"error\":\"keep evidence\"}" : id + " operations"));
    }
    var request = ChatRequest.builder().messages(messages).build();
    var overflow = new dev.langchain4j.exception.HttpException(400,
        "{\"error\":{\"code\":\"context_length_exceeded\"}}");
    ChatModel sync = mock(ChatModel.class);
    StreamingChatModel stream = mock(StreamingChatModel.class);
    var defaults = DefaultChatRequestParameters.builder().maxOutputTokens(100).build();
    when(sync.defaultRequestParameters()).thenReturn(defaults);
    when(stream.defaultRequestParameters()).thenReturn(defaults);
    when(sync.chat(any(ChatRequest.class))).thenThrow(overflow);
    org.mockito.Mockito.doAnswer(call -> {
      ((StreamingChatResponseHandler) call.getArgument(1)).onError(overflow); return null;
    }).when(stream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    assertThrows(dev.langchain4j.exception.HttpException.class, () -> new RateLimitChatModel(sync, config,
        seconds -> {}, new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).chat(request));
    var handler = mock(StreamingChatResponseHandler.class);
    new RateLimitStreamingChatModel(stream, config, seconds -> {}, new RateLimitErrorClassifier(),
        new RateLimitWaitPolicy()).chat(request, handler);
    verify(handler).onError(overflow);
    var sent = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
    verify(sync, org.mockito.Mockito.times(2)).chat(sent.capture());
    verify(stream, org.mockito.Mockito.times(2)).chat(sent.capture(), any(StreamingChatResponseHandler.class));
    for (int i : java.util.List.of(1, 3)) {
      ChatRequest rebuilt = sent.getAllValues().get(i);
      assertEquals(messages.getFirst(), rebuilt.messages().getFirst());
      assertEquals(messages.get(4), rebuilt.messages().get(4));
      assertEquals(messages.get(6), rebuilt.messages().get(6));
      assertEquals(messages.getLast(), rebuilt.messages().getLast());
      org.junit.jupiter.api.Assertions.assertNotEquals(messages.get(2), rebuilt.messages().get(2));
    }
    org.junit.jupiter.api.Assertions.assertNotEquals(sent.getAllValues().get(0).messages(),
        sent.getAllValues().get(1).messages());
    org.mockito.Mockito.reset(sync);
    when(sync.defaultRequestParameters()).thenReturn(defaults);
    when(sync.chat(any(ChatRequest.class))).thenThrow(overflow);
    assertThrows(dev.langchain4j.exception.HttpException.class, () -> new RateLimitChatModel(sync, config,
        seconds -> {}, new RateLimitErrorClassifier(), new RateLimitWaitPolicy())
        .chat(ChatRequest.builder().messages(messages.getFirst()).build()));
    verify(sync).chat(any(ChatRequest.class));
  }

  @Test
  void overflowRebuildKeepsSourcesAndLatestReadWithoutDuplicatingOldResults() {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().contextTokens()).thenReturn(4000);
    when(config.llm().contextHeadroomTokens()).thenReturn(128);
    var first = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
        .id("read-1").name("readRequirementDraft").arguments("{}").build();
    var second = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
        .id("read-2").name("readRequirementDraft").arguments("{}").build();
    String source = "Keep accessControlType=NONE after confirmation. Do not log bodies. Literal: $.Subject.";
    String latest = "{\"accepted\":\"" + source + "\"}";
    var old = dev.langchain4j.data.message.ToolExecutionResultMessage.from(first, "old words ".repeat(4000));
    var request = ChatRequest.builder().messages(
        dev.langchain4j.data.message.SystemMessage.from("Preserve accepted facts and exact source."),
        UserMessage.from(source), dev.langchain4j.data.message.AiMessage.from(first), old,
        UserMessage.from("Keep the unrelated facts and change the name to Task"),
        dev.langchain4j.data.message.AiMessage.from(second),
        dev.langchain4j.data.message.ToolExecutionResultMessage.from(second, latest))
        .parameters(DefaultChatRequestParameters.builder().maxOutputTokens(1000).build()).build();
    var prepared = new LlmRequestBudget(config).prepare(request);
    assertEquals(source, ((UserMessage) prepared.messages().get(1)).singleText());
    assertEquals(request.messages().get(4), prepared.messages().get(4));
    var replaced = (dev.langchain4j.data.message.ToolExecutionResultMessage) prepared.messages().get(3);
    assertEquals(first.id(), replaced.id());
    org.junit.jupiter.api.Assertions.assertTrue(replaced.text().contains("read-2"));
    assertEquals(latest, ((dev.langchain4j.data.message.ToolExecutionResultMessage)
        prepared.messages().getLast()).text());
    assertEquals(old, request.messages().get(3));
  }

  @Test
  void providerContextOverflowUsesStructuredErrorCode() {
    var overflow = new dev.langchain4j.exception.HttpException(400,
        "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"Provider refused input\"}}");
    org.junit.jupiter.api.Assertions.assertTrue(LlmRequestBudget.isOverflow(overflow));
    org.junit.jupiter.api.Assertions.assertFalse(LlmRequestBudget.isOverflow(
        new dev.langchain4j.exception.HttpException(400,
            "{\"error\":{\"code\":\"invalid_request_error\",\"message\":\"context_length_exceeded\"}}")));
    org.junit.jupiter.api.Assertions.assertFalse(LlmRequestBudget.isOverflow(
        new dev.langchain4j.exception.HttpException(400, "null")));
    for (String body : java.util.List.of("", "   ")) {
      org.junit.jupiter.api.Assertions.assertFalse(LlmRequestBudget.isOverflow(
          new dev.langchain4j.exception.HttpException(400, body)));
    }
    org.junit.jupiter.api.Assertions.assertFalse(LlmRequestBudget.isOverflow(
        new dev.langchain4j.exception.HttpException(400, null)));
  }

  @Test
  void oversizedRequiredInputNeverReachesEitherProvider() {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().contextTokens()).thenReturn(10000);
    when(config.llm().transientRetry().backoffSeconds()).thenReturn("2,5,10");
    ChatModel sync = mock(ChatModel.class);
    StreamingChatModel stream = mock(StreamingChatModel.class);
    var defaults = DefaultChatRequestParameters.builder().maxOutputTokens(1000).build();
    when(sync.defaultRequestParameters()).thenReturn(defaults);
    when(stream.defaultRequestParameters()).thenReturn(defaults);
    ChatRequest request = ChatRequest.builder().messages(UserMessage.from("many words ".repeat(10000))).build();
    var syncWrapper = new RateLimitChatModel(sync, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
    var streamWrapper = new RateLimitStreamingChatModel(stream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
    assertThrows(IllegalArgumentException.class, () -> syncWrapper.chat(request));
    assertThrows(IllegalArgumentException.class,
        () -> streamWrapper.chat(request, mock(StreamingChatResponseHandler.class)));
    verify(sync, never()).chat(any(ChatRequest.class));
    verify(stream, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  @Test
  void bothProviderWrappersSendDeclaredNestedRequiredFields() {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().transientRetry().backoffSeconds()).thenReturn("2,5,10");
    var note = JsonObjectSchema.builder().addStringProperty("targetKind")
        .addStringProperty("targetId").addStringProperty("summary").build();
    var tool = ToolSpecification.builder().name("captureDesignPlan")
        .parameters(JsonObjectSchema.builder().addProperty("capture", JsonObjectSchema.builder()
            .addProperty("notes", dev.langchain4j.model.chat.request.json.JsonArraySchema.builder()
                .items(note).build()).build()).build()).build();
    var request = ChatRequest.builder().messages(UserMessage.from("Approved targets"))
        .parameters(OpenAiChatRequestParameters.builder().maxOutputTokens(24000).reasoningEffort("none")
            .toolSpecifications(tool).build()).build();
    ChatModel sync = mock(ChatModel.class);
    StreamingChatModel stream = mock(StreamingChatModel.class);
    when(sync.defaultRequestParameters()).thenReturn(OpenAiChatRequestParameters.builder().build());
    when(stream.defaultRequestParameters()).thenReturn(OpenAiChatRequestParameters.builder().build());
    new RateLimitChatModel(sync, config, seconds -> {}, new RateLimitErrorClassifier(),
        new RateLimitWaitPolicy()).chat(request);
    new RateLimitStreamingChatModel(stream, config, seconds -> {}, new RateLimitErrorClassifier(),
        new RateLimitWaitPolicy()).chat(request, mock(StreamingChatResponseHandler.class));
    var sent = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
    verify(sync).chat(sent.capture());
    verify(stream).chat(sent.capture(), any(StreamingChatResponseHandler.class));
    for (ChatRequest packet : sent.getAllValues()) {
      var capture = (JsonObjectSchema) packet.parameters().toolSpecifications().getFirst()
          .parameters().properties().get("capture");
      var notes = (dev.langchain4j.model.chat.request.json.JsonAnyOfSchema) capture.properties().get("notes");
      var array = (dev.langchain4j.model.chat.request.json.JsonArraySchema) notes.anyOf().getFirst();
      assertEquals(java.util.List.of("targetKind", "targetId", "summary"),
          ((JsonObjectSchema) array.items()).required());
      assertEquals(24000, packet.parameters().maxOutputTokens());
      assertEquals("none", ((OpenAiChatRequestParameters) packet.parameters()).reasoningEffort());
    }
  }

  @Test
  void shortAnalysisCeilingPreservesGeneratorOutputAndProviderSettings() {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().analysisOutputTokens()).thenReturn(4096);
    var budget = new LlmRequestBudget(config);
    var capture = ToolSpecification.builder().name("captureRequirementBrief")
        .parameters(JsonObjectSchema.builder().addProperty("capture", JsonObjectSchema.builder().build()).build())
        .build();
    ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Complete input"))
        .parameters(OpenAiChatRequestParameters.builder().maxOutputTokens(30000).reasoningEffort("none")
            .toolSpecifications(capture).build()).build();
    var analysis = budget.prepare(request);
    assertEquals(4096, analysis.parameters().maxOutputTokens());
    assertEquals("none", ((OpenAiChatRequestParameters) analysis.parameters()).reasoningEffort());
    var generator = request.toBuilder().parameters(OpenAiChatRequestParameters.builder()
        .maxOutputTokens(30000).reasoningEffort("none").build()).build();
    assertEquals(30000, budget.prepare(generator).parameters().maxOutputTokens());
  }
}
