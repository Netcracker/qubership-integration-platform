package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.qubership.integration.platform.ai.plan.RequirementCapabilityConsistencyTest.input;
import static org.qubership.integration.platform.ai.plan.RequirementCapabilityConsistencyTest.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.productpipeline.capability.*;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementCapabilityConsistencyProviderTest {
  private static final String SOURCE = "Receive POST /work. Invoke the existing catalog operation. Do not add native senders. No IDS.";
  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject RequirementDiscoveryCapability discovery;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock RequirementDraftTool bindingAdapter;

  @Test
  void theFirstActualNextRequestCarriesTheNativeConflictAndPreservesTheAnchorDuringCorrection() throws Exception {
    String chat = "capability-conflict-" + UUID.randomUUID();
    begin(chat);
    var limits = limits();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("contradictory-capture", request -> {}, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", input(true)))),
        Turn.tool("native-conflict", request -> {
          var rejected = lastTool(request);
          assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
          assertTrue(rejected.path("issues").toString().contains("CAPABILITY_ANCHOR_CONFLICT"), rejected.toString());
          assertTrue(rejected.path("issues").toString().contains("remoteAnchor"), rejected.toString());
          assertTrue(rejected.path("issues").toString().contains("/draft/capabilities/remote/capabilityKey"), rejected.toString());
          assertTrue(drafts.get(chat).isEmpty());
          assertEquals(rejected.path("issues"), artifacts.latest(sessions.active(chat),
              CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload().path("issues"));
          assertTrue(drafts.repairConstraints(chat).facts().contains(input(true).facts().getLast()));
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", input(false)))),
        Turn.tool("preserved-correction", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals(input(true).facts(), drafts.get(chat).orElseThrow().authoredDraft().facts());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("continued", request -> assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString()), "Requirements are ready."));
    run(chat, script);
    assertEquals(4, script.requests().size());
    assertEquals(limits, limits());
  }

  @Test
  void legacyCleanupUsesTheExistingOriginalSourceAmendmentAfterPreciseNativeRefusalsInTheSameRun() throws Exception {
    String chat = "legacy-capability-conflict-" + UUID.randomUUID();
    var source = begin(chat);
    drafts.put(chat, RequirementCaptureProjection.toDraft(input(true), null, List.of(), "1", "hash"));
    var original = drafts.latestRevision(chat).orElseThrow();
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limits = limits();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("legacy-cleanup", request -> {}, "updateRequirementDraft", json.writeValueAsString(Map.of("changes",
            update(List.of(new CapabilityInput("remote", "http-sender", null, null, null, null, null, null)), List.of(), List.of())))),
        Turn.tool("conflict-before-protection", request -> {
          var rejected = lastTool(request);
          assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
          assertTrue(rejected.path("issues").toString().contains("CAPABILITY_ANCHOR_CONFLICT"), rejected.toString());
          assertTrue(rejected.path("issues").toString().contains("/capabilities/remote"), rejected.toString());
          assertEquals(original.reference(), drafts.latestRevision(chat).orElseThrow().reference());
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", update(List.of(), List.of("remote"), List.of())))),
        Turn.tool("existing-amendment-route", request -> {
          var rejected = lastTool(request);
          assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
          assertTrue(rejected.path("issues").toString().contains("AUTHOR_AMENDMENT_REQUIRED"), rejected.toString());
          assertTrue(rejected.path("issues").toString().contains("CAPABILITY_ANCHOR_CONFLICT"), rejected.toString());
          assertEquals(input(true), drafts.get(chat).orElseThrow().authoredDraft());
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", update(List.of(), List.of("remote"),
            List.of(new AmendmentInput("/capabilities/remote", source.turnId(), "Do not add native senders.")))))),
        Turn.tool("same-progress", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals(input(false), drafts.get(chat).orElseThrow().authoredDraft());
          assertEquals(original, artifacts.get(original.compilationId(), original.reference()).orElseThrow());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("continued", request -> assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString()), "Requirements are ready."));
    run(chat, script);
    assertEquals(5, script.requests().size());
    assertEquals(limits, limits());
    assertEquals(input(false).facts(), drafts.get(chat).orElseThrow().authoredDraft().facts());
    assertEquals(2, artifacts.history(original.compilationId(), CompilationArtifacts.Kind.REQUIREMENT_DRAFT).size());
  }

  private org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Input begin(String chat) {
    var source = conversations.archive().beginInput(chat, SOURCE, null);
    conversations.archive().resolveInput(chat, source, SOURCE); drafts.beginTurn(chat, SOURCE);
    when(bindingAdapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenAnswer(invocation -> {
      DraftInput input = invocation.getArgument(0);
      return input.capabilities().stream().anyMatch(capability -> "remote".equals(capability.interactionId())) ? List.of() : List.of(
          new CatalogBindingHint("3", "remote", "remote", "Process work", "system", "group", "spec", "operation",
              "http", "POST", "/process", "2024.4", Instant.EPOCH, "catalog-evidence"));
    });
    return source;
  }

  private void run(String chat, ScriptedStreamingChatModel script) {
    doAnswer(call -> { script.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1)); return null; })
        .when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var signals = discovery.execute(new StageExecutionContext("run-" + chat, chat, "requirement-discovery", "execution", "attempt",
        null, null, List.of(), Map.of("userText", SOURCE))).collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
        .findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass(), outcome.toString());
  }

  private JsonNode lastTool(ChatRequest request) {
    try { return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow().text()); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private Map<String, Object> limits() { return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
      "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults()); }
}
