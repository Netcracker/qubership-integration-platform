package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.*;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementPendingInitialRepairProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject ToolCallFingerprintStore fingerprints;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @Test
  void actualNextProviderRequestReadsTheNativeReferenceThenFocusesAndContinues() throws Exception {
    String chat = "pending-initial-provider-" + UUID.randomUUID();
    var initial = RequirementPendingInitialRepairTest.input("missing-owner");
    var corrected = RequirementPendingInitialRepairTest.input("entry");
    String source = corrected.facts().stream().map(FactInput::text)
        .collect(java.util.stream.Collectors.joining("\n")) + "\nProceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    String quotedSource = json.writeValueAsString(source);
    var limits = limits();
    var patch = new AtomicReference<String>();
    var originalAttempt = new AtomicReference<CompilationArtifacts.Revision>();
    var credits = new java.util.concurrent.atomic.AtomicInteger();
    String originalArguments = json.writeValueAsString(Map.of("draft", initial));
    var script = new ScriptedStreamingChatModel(
        Turn.tool("initial", request -> {}, "captureRequirementDraft", originalArguments),
        Turn.tool("read-current-pending", request -> {
          var refused = lastTool(request, "captureRequirementDraft");
          assertFalse(refused.path("accepted").asBoolean());
          assertTrue(refused.path("issues").findValuesAsText("code").contains("INVALID_ELEMENT_OWNER"));
          assertFalse(refused.has("pendingProposal"), "Do not duplicate the complete candidate in every refusal.");
          assertTrue(request.messages().toString().contains(quotedSource), request.messages().toString());
          var historical = request.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
              .flatMap(message -> message.toolExecutionRequests().stream()).filter(call -> "initial".equals(call.id()))
              .findFirst().orElseThrow();
          assertEquals(originalArguments, historical.arguments());
          var saved = artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          originalAttempt.set(saved);
          assertEquals(refused.path("issues"), saved.payload().path("issues"));
          assertFalse(saved.payload().has("resolvedCandidate"));
          credits.set(fingerprints.softCreditsUsed(chat));
          assertTrue(credits.get() > 0);
        }, "readRequirementDraft", "{}"),
        new Turn("focused-native-proposal", request -> {
          var read = lastTool(request, "readRequirementDraft");
          var pending = read.path("pendingProposal");
          assertEquals(json.valueToTree(originalAttempt.get().reference()), pending.path("attempt"));
          assertEquals(json.valueToTree(initial), pending.path("draft"));
          assertTrue(pending.path("base").isNull());
          assertEquals(input.generation(), pending.path("sourceGeneration").asText());
          assertEquals(input.turnId(), pending.path("sourceTurnId").asText());
          assertTrue(drafts.latestRevision(chat).isEmpty());
          assertEquals(credits.get(), fingerprints.softCreditsUsed(chat));
          var advertised = request.parameters().toolSpecifications().stream()
              .filter(tool -> "updateRequirementDraft".equals(tool.name())).findFirst().orElseThrow();
          assertTrue(advertised.description().contains("pendingProposal.attempt"));
          var actualChanges = assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonObjectSchema.class,
              advertised.parameters().properties().get("changes"));
          assertFalse(actualChanges.required().contains("pendingAttempt"));
          assertInstanceOf(dev.langchain4j.model.chat.request.json.JsonAnyOfSchema.class,
              actualChanges.properties().get("pendingAttempt"));
          var changes = json.<ObjectNode>valueToTree(new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
              List.of(), List.of(corrected.facts().getLast()), List.of(), List.of(), List.of(), List.of(), List.of(),
              List.of(), null, List.of()));
          changes.set("pendingAttempt", pending.path("attempt"));
          try { patch.set(json.writeValueAsString(Map.of("changes", changes))); }
          catch (Exception error) { throw new AssertionError(error); }
        }, handler -> handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(
            ToolExecutionRequest.builder().id("focused").name("updateRequirementDraft").arguments(patch.get()).build())).build())),
        Turn.tool("continue", request -> {
          var accepted = lastTool(request, "updateRequirementDraft");
          assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
          assertEquals(json.valueToTree(corrected), accepted.path("draft"));
          assertEquals(corrected, drafts.get(chat).orElseThrow().authoredDraft());
          var saved = artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          try { assertEquals(json.readTree(patch.get()).path("changes"), saved.payload().path("arguments")); }
          catch (Exception error) { throw new AssertionError(error); }
          assertEquals(json.valueToTree(corrected), saved.payload().path("resolvedCandidate"));
          assertTrue(saved.inputs().contains(originalAttempt.get().reference()));
          assertEquals(originalAttempt.get(), artifacts.get(originalAttempt.get().compilationId(), originalAttempt.get().reference()).orElseThrow());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("complete", request -> assertTrue(lastTool(request, "finishRequirementDiscoveryTurn")
            .path("accepted").asBoolean()), "The requirements are ready to continue."));
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      script.chat(request, handler);
      return null;
    })
        .when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters())
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    assertEquals(5, script.requests().size());
    assertEquals(RequirementDiscoveryDirective.CONTINUE, drafts.turnDirective(chat));
    assertEquals(limits, limits());
  }

  private JsonNode lastTool(ChatRequest request, String name) {
    var tool = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast).reduce((first, last) -> last).orElseThrow();
    assertEquals(name, tool.toolName());
    try { return json.readTree(tool.text()); } catch (Exception error) { throw new AssertionError(error); }
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }
}
