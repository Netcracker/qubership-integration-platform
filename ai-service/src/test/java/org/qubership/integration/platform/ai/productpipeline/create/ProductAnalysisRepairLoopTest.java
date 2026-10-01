package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.llm.agent.ProductDiscoveryAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

@QuarkusTest
class ProductAnalysisRepairLoopTest {

  private static final String CHAT = "analysis-repair-loop-test";
  @InjectMock RateLimitStreamingChatModel model;
  @Inject ProductDiscoveryAgent agent;
  @Inject org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent gather;
  @InjectMock org.qubership.integration.platform.ai.chat.conversation.ConversationArchive archive;
  @Inject CaptureRepairRunner runner;
  @Inject CaptureSession session;
  @Inject CaptureAttemptFeedbackStore feedback;

  @AfterEach
  void clear() {
    ProductCapabilityCaptureContext.unbind(CHAT);
    session.clear(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CHAT));
    feedback.clearPlan(CHAT);
  }

  @Test
  void statelessGatherRegistersCatalogAndSourceTools() {
    org.mockito.Mockito.when(archive.read(org.mockito.ArgumentMatchers.anyString())).thenReturn(
        new org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document(
            1, "generation", List.of(), List.of(), List.of(), List.of()));
    List<ChatRequest> requests = new ArrayList<>();
    doAnswer(call -> {
      requests.add(call.getArgument(0));
      StreamingChatResponseHandler handler = call.getArgument(1);
      handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Gather completed.")).build());
      return null;
    }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(CHAT, "Complete requirement packet", new dev.langchain4j.invocation.InvocationParameters()).collect().asList().await().atMost(Duration.ofSeconds(20));
    assertEquals(1, requests.size());
    var names = requests.getFirst().parameters().toolSpecifications().stream().map(tool -> tool.name()).toList();
    assertTrue(names.containsAll(List.of("captureRequirementDraft", "readRequirementSource",
        "listCatalogOperations", "resolveApiOperation", "finishRequirementDiscoveryTurn",
        "searchRequirementKnowledge")), names.toString());
  }

  @Test
  void malformedCaptureStopsNativeLoopAndRepairKeepsCompleteInput() {
    List<ChatRequest> requests = new ArrayList<>();
    String malformed = "{" + "x".repeat(100_000);
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      requests.add(request);
      String arguments = requests.size() == 1 ? malformed : """
          {"capture":{"goal":"Greetings","summary":"Return Hello world!",
          "inputs":["GET /greetings"],"assumptions":[],"citations":[]}}
          """;
      handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(
          ToolExecutionRequest.builder().id("call-" + requests.size())
              .name("captureRequirementBrief").arguments(arguments).build())).build());
      return null;
    }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

    var draft = RequirementFactFixtures.greetingsApprovedDraft();
    var context = ProductCapabilityCaptureContext.bindAnalysis("run", CHAT, draft, "draft-ref",
        "attempt-1", payload -> {});
    ProductCapabilityCaptureContext.Binding binding = context.get(ProductCapabilityCaptureContext.CONTEXT_KEY);
    var parameters = ProductCapabilityCaptureContext.invocationParameters(binding);
    String complete = RequirementAnalysisCapability.buildAnalysisUserMessage(draft)
        + "\nSelected artifact: draft-ref\nKnowledge version: fixed-knowledge-version";
    CaptureKey key = CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CHAT);
    runner.runWithRepair(message -> agent.chat(CHAT, message, parameters),
        () -> session.isPresent(key), () -> feedback.lastPlanFailure(CHAT), () -> {},
        "captureRequirementBrief", complete, true,
        rejection -> complete + "\nRepair captureRequirementBrief:\n" + rejection.summary(),
        () -> {}, 1).collect().asList().await().atMost(Duration.ofSeconds(20));

    assertEquals(2, requests.size());
    for (ChatRequest request : requests) {
      String userText = request.messages().stream().filter(UserMessage.class::isInstance)
          .map(UserMessage.class::cast).map(UserMessage::singleText)
          .reduce("", (left, right) -> left + right);
      assertTrue(userText.contains("draft-ref"));
      assertTrue(userText.contains("fixed-knowledge-version"));
      assertTrue(userText.contains("Hello world!"));
      assertFalse(request.messages().toString().contains(malformed));
    }
    assertEquals(draft.facts(), session.get(key, RequirementBrief.class).orElseThrow().facts());
  }
}
