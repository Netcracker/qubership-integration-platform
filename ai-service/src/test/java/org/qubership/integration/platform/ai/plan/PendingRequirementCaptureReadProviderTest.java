package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class PendingRequirementCaptureReadProviderTest {
  private static final String LITERAL = "pending-literal-" + "v".repeat(1_300) + "-complete";
  private static final String SCRIPT = "def required = '" + LITERAL + "'\nexchange.message.body = required\n";

  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject ArtifactBlobStore blobs;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @Test
  void rejectedOnlyProposalReachesTheProviderThroughTheReadToolWithoutBecomingAccepted() throws Exception {
    String chat = "pending-read-" + UUID.randomUUID();
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Use POST /pending-capture with externalRoute=false.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false")), List.of()));
    var scriptFact = new FactInput("local-script", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "After entry, run this exact script:\n" + SCRIPT, null, null,
        new RequirementElementIntent("local-script", "script", RequirementElementIntent.Placement.LOCAL_NODE,
            "missing-owner", null, "entry", null, List.of(new PlanProperty("script", SCRIPT)), List.of(LITERAL)));
    var proposed = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /pending-capture", null, null, null)), List.of()), List.of(endpoint, scriptFact),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/pending-capture", null, null, null)), List.of(), new DraftSettings(false, null));
    String source = endpoint.text() + "\n" + scriptFact.text() + "\nProceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    JsonNode sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var rejected = new AtomicReference<JsonNode>();
    var attempt = new AtomicReference<Revision>();
    var persistedBeforeRead = new AtomicReference<Map<String, String>>();
    var constraintsBeforeRead = new AtomicReference<JsonNode>();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("capture", request -> {
          assertTrue(request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
              .map(UserMessage::singleText).anyMatch(text -> text.contains(LITERAL)));
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", proposed))),
        Turn.tool("read", request -> {
          JsonNode refusal = lastTool(request, "captureRequirementDraft");
          rejected.set(refusal);
          assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
          assertEquals("EMPTY", refusal.path("readiness").asText());
          assertTrue(refusal.path("draft").isNull());
          assertTrue(refusal.path("revision").isNull());
          assertTrue(refusal.path("issues").findValuesAsText("code").contains("INVALID_ELEMENT_OWNER"), refusal.toString());
          Revision immutable = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          attempt.set(immutable);
          var payload = immutable.payload();
          assertFalse(payload.path("accepted").asBoolean());
          assertEquals("captureRequirementDraft", payload.path("toolName").asText());
          assertEquals(input.generation(), payload.path("sourceGeneration").asText());
          assertEquals(input.turnId(), payload.path("sourceTurnId").asText());
          assertTrue(payload.path("acceptedDraftReference").isNull());
          assertEquals(json.valueToTree(proposed), payload.path("arguments"));
          assertEquals(refusal.path("issues"), payload.path("issues"));
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
          assertTrue(drafts.latestRevision(chat).isEmpty());
          assertFalse(drafts.wasCapturedThisTurn(chat));
          constraintsBeforeRead.set(json.valueToTree(drafts.repairConstraints(chat)));
          persistedBeforeRead.set(persistedState());
        }, "readRequirementDraft", "{}"),
        Turn.text("pending-proposal", request -> {
          JsonNode result = lastTool(request, "readRequirementDraft");
          JsonNode pending = result.path("pendingProposal");
          assertTrue(pending.isObject(), result.toString());
          assertEquals(json.valueToTree(proposed), pending.path("draft"));
          assertEquals(json.valueToTree(attempt.get().reference()), pending.path("attempt"));
          assertEquals(input.generation(), pending.path("sourceGeneration").asText());
          assertEquals(input.turnId(), pending.path("sourceTurnId").asText());
          assertTrue(pending.path("base").isNull());
          assertEquals("captureRequirementDraft", pending.path("toolName").asText());
          assertEquals(rejected.get().path("issues"), pending.path("issues"));
          assertTrue(pending.path("draft").toString().contains(LITERAL));
          assertEquals("EMPTY", result.path("readiness").asText());
          assertTrue(result.path("draft").isNull(), "Pending content must stay separate from the accepted draft.");
          assertTrue(result.path("revision").isNull());
          assertFalse(result.path("changed").asBoolean());
          assertTrue(drafts.latestRevision(chat).isEmpty());
          assertTrue(drafts.get(chat).isEmpty());
          assertFalse(drafts.wasCapturedThisTurn(chat));
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
          assertEquals(constraintsBeforeRead.get(), json.valueToTree(drafts.repairConstraints(chat)));
          assertEquals(attempt.get(), artifacts.get(attempt.get().compilationId(), attempt.get().reference()).orElseThrow());
          assertEquals(persistedBeforeRead.get(), persistedState(), "Read must not append or rewrite durable authority, source, or approvals.");
        }, "The rejected proposal is available for correction; it is not an accepted requirement draft."));
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      script.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters())
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    assertEquals(3, script.requests().size());
    assertEquals(limitsBefore, limits());
  }

  private JsonNode lastTool(ChatRequest request, String tool) {
    try {
      var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      assertEquals(tool, result.toolName());
      return json.readTree(result.text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private Map<String, String> persistedState() {
    var state = new LinkedHashMap<String, String>();
    for (String key : blobs.list("")) {
      var blob = blobs.getVersioned(key).orElseThrow();
      state.put(key, blob.version() + ":" + Base64.getEncoder().encodeToString(blob.content()));
    }
    return state;
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }
}
