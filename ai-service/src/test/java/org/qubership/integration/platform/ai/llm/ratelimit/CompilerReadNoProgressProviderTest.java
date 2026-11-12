package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;
import org.qubership.integration.platform.ai.compiler.capture.*;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.CompilerSkillAgent;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

@QuarkusTest
@TestProfile(RecoveryAcceptanceProvider.Profile.class)
class CompilerReadNoProgressProviderTest {
  private static final String OWNER = "cip-service-call-generator";
  @Inject CompilerSkillAgent agent;
  @Inject CaptureSession captures;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject ToolReadProgress readProgress;
  @Inject LlmRunBudget budget;
  @Inject ObjectMapper json;
  @Inject AppConfig config;
  @Inject @io.quarkiverse.langchain4j.ModelName("openai-upstream")
  dev.langchain4j.model.chat.StreamingChatModel upstreamStreaming;
  @Inject RateLimitBackoffSleeper sleeper;
  @InjectMock RateLimitStreamingChatModel streamingProvider;
  @InjectMock RateLimitChatModel diagnosisProvider;

  @Test
  void repeatedRealReadsReachNativeCaptureInOneAgentInvocationWithoutDiagnosis() throws Exception {
    String chat = "read-loop-" + UUID.randomUUID();
    String run = "run-" + UUID.randomUUID();
    String source = "Preserve all original requirements. Read the native method contract. Do not invent a service call.";
    String args = "{\"elementType\":\"http-trigger\",\"propertyPath\":\"httpMethodRestrict\"}";
    String noop = json.writeValueAsString(Map.of("patch", Map.of("patchId", "unused-call", "ownerCapabilityId", OWNER,
        "notApplicable", true, "nodePatches", List.of(), "edgePatches", List.of(), "propertyPatches", List.of(),
        "chainPatches", List.of(), "usedKnowledgeRefs", List.of())));
    var script = new ScriptedStreamingChatModel(
        Turn.tool("read-one", request -> assertSource(request, source), "describeElementProperty", args),
        Turn.tool("read-two", request -> assertNativeResult(request, "read-one"), "describeElementProperty", args),
        Turn.tool("read-three", request -> assertNativeResult(request, "read-two"), "describeElementProperty", args),
        Turn.tool("normal-capture", request -> {
          assertSource(request, source);
          assertNativeResult(request, "read-three");
          String recovery = assertInstanceOf(UserMessage.class, request.messages().getLast()).singleText();
          String packet = recovery.lines().filter(line -> line.startsWith("{"))
              .findFirst().orElseThrow(() -> new AssertionError(recovery));
          var retained = assertDoesNotThrow(() -> json.readTree(packet));
          var tools = request.parameters().toolSpecifications().stream().map(tool -> tool.name()).toList();
          assertAll(
              () -> assertTrue(recovery.contains("Retained tool results"), recovery),
              () -> assertEquals("describeElementProperty", retained.path("tool").asText()),
              () -> assertEquals("httpMethodRestrict", retained.path("arguments").path("propertyPath").asText()),
              () -> assertEquals(
                  "^$|^(POST|GET|PUT|DELETE|PATCH|HEAD|OPTIONS)(,(POST|GET|PUT|DELETE|PATCH|HEAD|OPTIONS))*$",
                  retained.path("result").path("data").path("schema").findValue("pattern").asText(), packet),
              () -> assertTrue(tools.containsAll(List.of("describeElementProperty", "captureGraphPatch")), tools.toString()));
        }, "captureGraphPatch", noop));
    var selected = RecoveryAcceptanceProvider.controlledPrefix(script, upstreamStreaming, 3, "read-loop", json);
    var boundary = RecoveryAcceptanceProvider.boundary(selected, config, sleeper, budget, readProgress);
    doAnswer(call -> {
      MDC.put(CompilerSkillMdc.CAPABILITY_ID, OWNER);
      boundary.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
      return null;
    }).when(streamingProvider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    budget.linkConversation(run, chat);
    try (var ignored = ToolSession.open(chat)) {
      MDC.put(CompilerSkillMdc.CAPABILITY_ID, OWNER);
      feedback.beginScope(chat, OWNER, Map.of("source", source, "runId", run));
      var accepted = assertThrows(CaptureValidationException.class, () -> agent.chat(chat, source).collect().asList()
          .await().atMost(RecoveryAcceptanceProvider.observation(config)));
      if (!RecoveryAcceptanceProvider.live()) script.assertComplete();
      var key = CaptureKey.capability(CaptureSlot.GRAPH_PATCH, chat, OWNER);
      var patch = captures.get(key, GraphPatch.class).orElseThrow(() -> new AssertionError(key));
      assertAll(
          () -> assertTrue(accepted.getMessage().startsWith("Graph patch captured (notApplicable): patchId='"),
              accepted.getMessage()),
          () -> assertTrue(accepted.getMessage().contains("patchId='" + patch.patchId() + "', rationale="),
              accepted.getMessage()),
          () -> assertEquals(OWNER, patch.ownerCapabilityId()));
      if (!RecoveryAcceptanceProvider.live()) assertEquals("unused-call", patch.patchId());
      if (!RecoveryAcceptanceProvider.live()) assertEquals(4, budget.state(run).requests());
      verify(diagnosisProvider, never()).chat(any(ChatRequest.class));
      assertTrue(feedback.lastPatchFailure(chat, OWNER).isEmpty());
      assertEquals(0, budget.state(run).grants());
      assertEquals(0, feedback.fingerprintStore().softCreditsUsed(chat));
      RecoveryAcceptanceProvider.evidence(json, "read-loop", "result", Map.of(
          "outcome", "PASS", "runId", run, "conversationId", chat,
          "realProvider", RecoveryAcceptanceProvider.live(), "controlledPrefixRequests", 3,
          "nativeCaptureAccepted", true, "diagnosisRequests", 0,
          "requests", budget.state(run).requests(), "graphDeltaCredited", false,
          "scope", "One compiler agent invocation with local read feedback and native capture"));
      String destination = System.getProperty("qip.test.read-progress-evidence");
      if (destination != null) json.writeValue(java.nio.file.Path.of(destination).toFile(), Map.of(
          "scope", "One actual compiler agent invocation, native tools, validators, and provider boundary",
          "runId", run, "diagnosisRequests", 0,
          "streamingRequests", script.requests().stream().map(request -> request.messages().toString()).toList(),
          "requests", budget.state(run).requests(), "grants", budget.state(run).grants(), "graphDeltaCredited", false,
          "acceptedPatchId", patch.patchId(), "acceptedCaptureSignal", accepted.getMessage(),
          "toolNames", script.requests().stream().map(request -> request.parameters().toolSpecifications().stream()
              .map(tool -> tool.name()).toList()).toList()));
    } finally {
      MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
      captures.clear(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, chat, OWNER));
    }
  }

  private static void assertSource(ChatRequest request, String source) {
    assertTrue(request.messages().stream().anyMatch(message -> message instanceof UserMessage user
        && user.hasSingleText() && user.singleText().startsWith(source)), request.messages().toString());
  }

  private static void assertNativeResult(ChatRequest request, String id) {
    assertTrue(request.messages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
        && id.equals(result.id()) && result.text().contains("httpMethodRestrict") && result.text().contains("pattern")),
        request.messages().toString());
  }
}
