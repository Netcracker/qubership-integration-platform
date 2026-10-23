package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.data.message.AiMessage;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.*;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.CompilerSkillAgent;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.recovery.*;

@QuarkusTest
@TestProfile(RecoveryAcceptanceProvider.Profile.class)
class CompilerReadNoProgressProviderTest {
  private static final String OWNER = "cip-service-call-generator";
  @Inject CompilerSkillAgent agent;
  @Inject FailureNarrativeAgent diagnosis;
  @Inject CaptureRepairRunner runner;
  @Inject CaptureSession captures;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject ToolReadProgress readProgress;
  @Inject LlmRunBudget budget;
  @Inject ObjectMapper json;
  @Inject AppConfig config;
  @Inject @io.quarkiverse.langchain4j.ModelName("openai-upstream")
  dev.langchain4j.model.chat.StreamingChatModel upstreamStreaming;
  @Inject @io.quarkiverse.langchain4j.ModelName("openai-upstream")
  dev.langchain4j.model.chat.ChatModel upstreamDiagnosis;
  @Inject RateLimitBackoffSleeper sleeper;
  @InjectMock RateLimitStreamingChatModel streamingProvider;
  @InjectMock RateLimitChatModel diagnosisProvider;

  @Test
  void repeatedRealReadsReachActualDiagnosisAndAnotherToolActionInTheSameRun() throws Exception {
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
        Turn.tool("different-action", request -> {
          assertSource(request, source);
          assertTrue(request.messages().toString().contains("Use the retained result"));
        }, "describeElementPatchSchema", "{\"elementType\":\"service-call\"}"),
        Turn.tool("normal-capture", request -> {
          assertTrue(request.messages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
              && "different-action".equals(result.id()) && result.text().contains("service-call")));
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
      Throwable stopped = assertThrows(RuntimeException.class, () ->
          agent.chat(chat, source).collect().asList().await().atMost(RecoveryAcceptanceProvider.observation(config)));
      var readFailure = ReadNoProgressException.find(stopped).orElseThrow();
      var cause = StageOutcome.invocationFailure(stopped).recoveryCause();
      assertNull(cause.captureFailure());
      assertEquals(3, script.requests().size());
      assertEquals(3, budget.state(run).requests());
      assertTrue(feedback.lastPatchFailure(chat, OWNER).isEmpty());
      var evidence = new RecoveryEvidence(1, "read-loop", cause.causeCode().name(), "design-execution", "design-execution",
          null, null, List.of(), List.of(new SemanticFinding("TOOL_READ_NO_PROGRESS", readFailure.getMessage(),
              "read-loop", OWNER, "", List.of(), List.of(), List.of(), "", Map.of(), List.of(),
              json.writeValueAsString(cause.findings().getFirst()))), null, List.of(), List.of());
      var context = new RecoveryContext(evidence, null, null, "en",
          List.of(new UserInput("source", "gather", source, java.time.Instant.EPOCH)));
      var diagnoses = new AtomicInteger();
      var diagnosticUpstream = org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
      org.mockito.Mockito.when(diagnosticUpstream.defaultRequestParameters()).thenReturn(
          RecoveryAcceptanceProvider.live() ? upstreamDiagnosis.defaultRequestParameters()
              : dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder().build());
      org.mockito.Mockito.when(diagnosticUpstream.chat(any(ChatRequest.class))).thenAnswer(call -> {
        diagnoses.incrementAndGet();
        ChatRequest request = call.getArgument(0);
        String actual = request.messages().toString();
        assertTrue(actual.contains("TOOL_READ_NO_PROGRESS"));
        assertTrue(actual.contains("read-three") && actual.contains("httpMethodRestrict"));
        assertTrue(actual.contains(source));
        var decision = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, null, List.of("read-loop"),
            RecoveryAction.PARK, List.of(), "", "Use the retained schema and a different action.");
        RecoveryAcceptanceProvider.evidence(json, "read-loop", "diagnosis-request-" + diagnoses.get(), Map.of(
            "messages", request.messages().stream().map(Object::toString).toList(),
            "realProvider", RecoveryAcceptanceProvider.live()));
        return RecoveryAcceptanceProvider.live() ? upstreamDiagnosis.chat(request)
            : ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(decision))).build();
      });
      var diagnosticBoundary = new RateLimitChatModel(diagnosticUpstream, config,
          sleeper,
          new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
      doAnswer(call -> diagnosticBoundary.chat(call.getArgument(0, ChatRequest.class)))
          .when(diagnosisProvider).chat(any(ChatRequest.class));
      var result = new FailureNarrative(diagnosis).diagnoseRecovery(run, context);
      assertEquals(FailureNarrative.RecoveryStatus.READY, result.status());
      assertTrue(diagnoses.get() >= 1);
      if (!RecoveryAcceptanceProvider.live()) assertEquals(1, diagnoses.get());
      // Exercise the existing producer retry seam; no graph mutation credit is claimed for NOT_APPLICABLE.
      runner.runWithRepair(message -> agent.chat(chat, message),
          () -> captures.isPresent(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, chat, OWNER)),
          () -> feedback.lastPatchFailure(chat, OWNER), () -> fail("Unexpected argument failure"),
          "captureGraphPatch", source + " Use the retained result and choose another action. "
              + "This controlled component probe has no service-call node to configure. "
              + "Read a different applicable native schema if necessary, then submit a NOT_APPLICABLE graph patch "
              + "for owner " + OWNER + " without inventing nodes or editing any requirement.")
          .collect().asList().await().atMost(RecoveryAcceptanceProvider.observation(config));
      if (!RecoveryAcceptanceProvider.live()) script.assertComplete();
      assertTrue(captures.isPresent(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, chat, OWNER)));
      if (!RecoveryAcceptanceProvider.live()) assertEquals(6, budget.state(run).requests());
      assertEquals(0, budget.state(run).grants());
      assertEquals(0, feedback.fingerprintStore().softCreditsUsed(chat));
      RecoveryAcceptanceProvider.evidence(json, "read-loop", "result", Map.of(
          "outcome", "PASS", "runId", run, "conversationId", chat,
          "realProvider", RecoveryAcceptanceProvider.live(), "controlledPrefixRequests", 3,
          "nativeCaptureAccepted", true, "diagnosisRequests", diagnoses.get(),
          "requests", budget.state(run).requests(), "graphDeltaCredited", false,
          "scope", "Registered components and existing retry seam; not a full pipeline run"));
      String destination = System.getProperty("qip.test.read-progress-evidence");
      if (destination != null) json.writeValue(java.nio.file.Path.of(destination).toFile(), Map.of(
          "scope", "Actual compiler agent, tools, validators, diagnostic agent, and resource boundaries; scripted providers",
          "runId", run, "cause", cause, "context", RecoveryContextProjector.project(context, json),
          "streamingRequests", script.requests().stream().map(request -> request.messages().toString()).toList(),
          "requests", budget.state(run).requests(), "grants", budget.state(run).grants(), "graphDeltaCredited", false));
    } finally {
      MDC.remove(CompilerSkillMdc.CAPABILITY_ID);
      captures.clear(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, chat, OWNER));
    }
  }

  private static void assertSource(ChatRequest request, String source) {
    assertTrue(request.messages().stream().anyMatch(message -> message instanceof UserMessage user
        && user.hasSingleText() && user.singleText().equals(source)));
  }

  private static void assertNativeResult(ChatRequest request, String id) {
    assertTrue(request.messages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
        && id.equals(result.id()) && result.text().contains("httpMethodRestrict") && result.text().contains("enum")));
  }
}
