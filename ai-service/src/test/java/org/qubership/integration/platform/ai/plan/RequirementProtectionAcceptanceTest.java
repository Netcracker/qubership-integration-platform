package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.*;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

@QuarkusTest
@TestProfile(RecoveryAcceptanceProvider.Profile.class)
class RequirementProtectionAcceptanceTest {
  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject RequirementCaptureToolProvider captureProvider;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject AppConfig config;
  @Inject LlmRunBudget budget;
  @Inject ToolReadProgress progress;
  @Inject RateLimitBackoffSleeper sleeper;
  @Inject @ModelName("openai-upstream") StreamingChatModel upstream;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock RequirementDraftTool adapter;

  @Test
  void persistedNativeConflictReachesRepairProviderAndContinuesWithoutChangingOtherRequirements() throws Exception {
    String chat = "protection-acceptance-" + UUID.randomUUID();
    String run = "run-" + UUID.randomUUID();
    String source = "Receive POST /work. Use errorThrowing=false on Remote request. "
        + "Pass every HTTP response to Render result and deliver its success and failure fields. No IDS.";
    var archived = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, archived, source);
    drafts.beginTurn(chat, source);
    ProductCapabilityCaptureContext.bindDiscovery(run, chat, ignored -> {});
    budget.linkConversation(run, chat);
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(RequirementFailureConsistencyAdmissionTest.bindings());
    var original = RequirementFailureConsistencyAdmissionTest.input();
    var corrected = RequirementFailureConsistencyAdmissionTest.withMode(original, ServiceCallFailureMode.INLINE_RESPONSE);
    try (var ignored = ToolSession.open(chat)) {
      var malformed = (ObjectNode) json.valueToTree(original);
      ((ObjectNode) malformed.at("/facts/2/elementIntent")).putNull("ownerInteractionId");
      var executor = captureProvider.provideTools(null).toolExecutorByName("captureRequirementDraft");
      String first = executor.execute(ToolExecutionRequest.builder().id("seed-first")
          .name("captureRequirementDraft").arguments(json.writeValueAsString(Map.of("draft", malformed))).build(), chat);
      assertFalse(json.readTree(first).path("accepted").asBoolean());
      assertNull(drafts.repairConstraints(chat));
      var sourceBefore = json.valueToTree(conversations.archive().read(chat));
      var script = new ScriptedStreamingChatModel(
          Turn.tool("native-conflict", request -> {}, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", original))),
          Turn.tool("repair", request -> checkRefusalAndRestoreLegacy(chat, request),
              "captureRequirementDraft", json.writeValueAsString(Map.of("draft", corrected))),
          Turn.tool("continue", request -> assertTrue(lastTool(request).path("accepted").asBoolean()),
              "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
          Turn.text("ready", request -> assertTrue(lastTool(request).path("accepted").asBoolean()), "Requirements are ready."));
      var selected = RecoveryAcceptanceProvider.controlledPrefix(script, upstream, 1, "requirement-protection", json);
      var boundary = RecoveryAcceptanceProvider.boundary(selected, config, sleeper, budget, progress);
      var requestCount = new java.util.concurrent.atomic.AtomicInteger();
      doAnswer(call -> {
        ChatRequest request = call.getArgument(0);
        if (requestCount.incrementAndGet() == 2 && RecoveryAcceptanceProvider.live()) checkRefusalAndRestoreLegacy(chat, request);
        boundary.chat(request, call.getArgument(1, StreamingChatResponseHandler.class));
        return null;
      }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
      String packet = prompts.wrap(chat, source, "en") + "\nControlled recovery probe: preserve the pending full proposal. "
          + "Repair only the reported native contradiction and finish the turn with CONTINUE. No author amendment exists.";
      gather.chat(chat, packet, new InvocationParameters()).collect().asList().await()
          .atMost(RecoveryAcceptanceProvider.observation(config));
      if (!RecoveryAcceptanceProvider.live()) script.assertComplete();
      assertEquals(corrected, drafts.get(chat).orElseThrow().authoredDraft());
      assertEquals(RequirementDiscoveryDirective.CONTINUE, drafts.turnDirective(chat));
      var sourceAfter = json.valueToTree(conversations.archive().read(chat));
      ((ObjectNode) sourceBefore.at("/turns/0")).set("consumedDraftReference", sourceAfter.at("/turns/0/consumedDraftReference"));
      assertEquals(sourceBefore, sourceAfter);
      var history = artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT);
      assertTrue(history.stream().anyMatch(row -> row.payload().path("accepted").asBoolean()));
      RecoveryAcceptanceProvider.evidence(json, "requirement-protection", "result", Map.of(
          "outcome", "PASS", "realProvider", RecoveryAcceptanceProvider.live(), "runId", run,
          "conversationId", chat, "controlledPrefixRequests", 1, "nativeCaptureAccepted", true,
          "sourcePreserved", true, "allOtherFieldsPreserved", true, "continued", true,
          "scope", "Registered requirement discovery and persisted protection; not a full pipeline run"));
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  private void checkRefusalAndRestoreLegacy(String chat, ChatRequest request) {
    var refusal = lastTool(request);
    assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
    assertTrue(refusal.path("issues").toString().contains("FAILURE_MODE_CONFLICT"));
    assertTrue(refusal.path("issues").toString().contains("/draft/flow/interactions/remote/failureMode"));
    var old = artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION).getLast();
    var protection = artifacts.payload(old, RequirementDraftStore.CaptureFactProtection.class);
    var legacy = (ObjectNode) protection.values().deepCopy();
    ((ObjectNode) legacy.at("/interactions/remote")).put("failureMode", "PROPAGATE");
    var restored = artifacts.append(new CompilationArtifacts.AppendCommand(old.compilationId(), old.kind(), "1",
        "requirement-capture-protection", "1", new RequirementDraftStore.CaptureFactProtection(
            protection.sourceGeneration(), protection.sourceTurnId(), protection.authorText(), protection.facts(), legacy,
            protection.baseIdentity()), List.of(), old.artifactId()));
    RecoveryAcceptanceProvider.evidence(json, "requirement-protection", "native-refusal-and-legacy-protection", Map.of(
        "actualNextRequest", request.messages().stream().map(Object::toString).toList(),
        "nativeRefusal", refusal, "restoredProtection", restored));
  }

  private com.fasterxml.jackson.databind.JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((a, b) -> b).orElseThrow().text());
    } catch (Exception failure) { throw new AssertionError(failure); }
  }
}
