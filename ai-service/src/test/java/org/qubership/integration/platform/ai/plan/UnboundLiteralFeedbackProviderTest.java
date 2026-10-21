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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
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
class UnboundLiteralFeedbackProviderTest {
  private static final List<String> UNBOUND = List.of("  pending \"quoted\"=value  ", "complete-" + "z".repeat(2_300));

  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @ParameterizedTest
  @ValueSource(strings = {"accepted-literal", "false-value", "full-text"})
  void nativeFeedbackReachesTheNextProviderRequestWithoutChangingPreservation(String mutation) throws Exception {
    String chat = "unbound-feedback-" + UUID.randomUUID();
    var acceptedLog = fact("accepted-log", "Log exactly accepted=literal. Preserve the original record.",
        new RequirementElementIntent("accepted-log", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, "entry", null, List.of(new PlanProperty("message", "accepted=literal")), List.of("accepted=literal")));
    var endpoint = fact("endpoint", "Use externalRoute=false for POST /literal-feedback.",
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false")), List.of()));
    var initial = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /literal-feedback", null, null, null)), List.of()), List.of(endpoint, acceptedLog),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/literal-feedback", null, null, null)), List.of(), new DraftSettings(false, null));
    String pendingText = "Log exactly " + String.join(" and ", UNBOUND) + " at Info level.";
    String source = endpoint.text() + " " + acceptedLog.text() + " " + pendingText + " Proceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    try (var ignored = ToolSession.open(chat)) {
      var saved = json.readTree(tools.captureRequirementDraft(initial));
      assertTrue(saved.path("accepted").asBoolean(), saved.toString());
    }
    Reference base = drafts.latestRevision(chat).orElseThrow().reference();
    JsonNode savedSource = json.valueToTree(conversations.archive().read(chat));
    var limits = Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
    var pending = fact("pending-log", pendingText,
        new RequirementElementIntent("pending-log", "log-record", RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, "accepted-log", null, List.of(new PlanProperty("logLevel", "Info")), UNBOUND));
    var bound = fact(pending.sourceFactId(), pending.text(), new RequirementElementIntent("pending-log", "log-record",
        RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, "accepted-log", null,
        List.of(new PlanProperty("logLevel", "Info"), new PlanProperty("message", json.writeValueAsString(UNBOUND))), UNBOUND));
    FactInput damaged = switch (mutation) {
      case "accepted-literal" -> fact(acceptedLog.sourceFactId(), acceptedLog.text(), new RequirementElementIntent(
          "accepted-log", "log-record", RequirementElementIntent.Placement.LOCAL_NODE, "entry", null, "entry", null,
          acceptedLog.elementIntent().requestedProperties(), List.of()));
      case "false-value" -> fact(endpoint.sourceFactId(), endpoint.text(), new RequirementElementIntent(
          "entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null,
          List.of(new PlanProperty("externalRoute", "true")), List.of()));
      default -> fact(acceptedLog.sourceFactId(), "Log exactly accepted=literal. Change the original record.", acceptedLog.elementIntent());
    };
    var refusal = new AtomicReference<JsonNode>();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("unbound", request -> {}, "updateRequirementDraft", arguments(update(List.of(pending), List.of()))),
        Turn.tool("protected", request -> {
          var result = lastTool(request);
          refusal.set(result);
          assertFalse(result.path("accepted").asBoolean(), result.toString());
          var issue = result.path("issues").get(0);
          assertEquals("UNBOUND_REQUIRED_LITERAL", issue.path("code").asText());
          assertEquals("/draft/facts/pending-log/elementIntent/requestedProperties", issue.path("path").asText());
          assertTrue(issue.path("message").asText().contains("/draft/facts/pending-log/elementIntent/exactValues"));
          assertTrue(issue.path("message").asText().contains(json.valueToTree(UNBOUND).toString()));
          assertTrue(issue.path("message").asText().contains("some checks may be deferred"));
          var attempt = artifacts.latest(sessions.active(chat), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload();
          assertEquals(attempt.path("issues"), result.path("issues"), "The first next provider request retains the complete native refusal.");
          assertEquals("/facts/pending-log/elementIntent/requestedProperties", RequirementDraftStore.requirementRepairPath(
              RequirementCaptureProjection.toDraft(new DraftInput(initial.flow(), List.of(endpoint, acceptedLog, pending),
                  initial.capabilities(), initial.openQuestions(), initial.settings()), null, List.of(), "1", "hash"),
              new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
                  issue.path("code").asText(), issue.path("message").asText(), true, null, "pending-log",
                  issue.path("path").asText())));
        }, "updateRequirementDraft", arguments(update(List.of(bound), List.of(damaged)))),
        Turn.text("retained", request -> {
          var result = lastTool(request);
          assertFalse(result.path("accepted").asBoolean(), result.toString());
          assertTrue(result.path("issues").toString().contains("REPAIR_FACT_LOSS"), result.toString());
          assertEquals(base, drafts.latestRevision(chat).orElseThrow().reference());
          assertEquals(initial, drafts.get(chat).orElseThrow().authoredDraft());
          assertEquals(savedSource, json.valueToTree(conversations.archive().read(chat)));
        }, "The native refusal and accepted requirements remain unchanged."));
    run(chat, source, script);
    assertTrue(refusal.get().path("repairConstraints").path("facts").toString().contains(UNBOUND.getLast()));
    var nextTurn = new ScriptedStreamingChatModel(Turn.text("pending", request -> {
      String text = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
          .map(UserMessage::singleText).reduce("", String::concat);
      assertTrue(text.contains("Pending preservation constraints copied from rejected proposals"), text);
      assertTrue(text.contains("some checks may be deferred"), text);
      assertTrue(text.contains(UNBOUND.getLast()), text);
      assertFalse(text.contains("Validated fields from rejected proposals"), text);
    }, "No requirements were removed."));
    run(chat, source, nextTurn);
    assertEquals(limits, Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults()));
  }

  private void run(String chat, String source, ScriptedStreamingChatModel script) {
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      script.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters())
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
  }

  private String arguments(DraftUpdate update) throws Exception {
    return json.writeValueAsString(Map.of("changes", update));
  }

  private static DraftUpdate update(List<FactInput> additions, List<FactInput> replacements) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), additions, replacements,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
  }

  private static FactInput fact(String id, String text, RequirementElementIntent intent) {
    return new FactInput(id, List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, text, null, null, intent);
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      var message = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      return json.readTree(message.text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }
}
