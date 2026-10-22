package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.nativeAnchorFact;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FieldMappingInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class MappingRepairFeedbackProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject RequirementDiscoveryCapability discovery;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @Test
  void exactForbiddenChangesAndAllowedScopeReachTheFirstNextRequestBeforeSameRunRepair() throws Exception {
    String chat = "mapping-repair-feedback-" + UUID.randomUUID();
    var protectedFact = new FactInput("retain-context", List.of("entry"), FactKind.BEHAVIOR,
        Polarity.POSITIVE, "Retain the complete original request context before delivery. "
            + "Preserve the supplied context. ".repeat(50) + "Keep the final context marker intact.");
    var mapping = mapping("receipt-map", "Return the saved receipt.", "$.receipt", null);
    var initial = new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /receipts", "Receive receipts", null, null),
        new InteractionInput("delivery", Direction.OUTBOUND, "Delivery", "POST /results", "Return results", null, null)),
        List.of(new TransitionInput("entry", "delivery"))),
        List.of(protectedFact, mapping, nativeAnchorFact("entry", "http-trigger"), nativeAnchorFact("delivery", "http-sender")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/receipts", null, null, null),
            new CapabilityInput("delivery", "http-sender", null, HttpMethod.POST, "http://localhost/results", null, null, null)),
        List.of(), new DraftSettings(false, null));
    String source = "Receive POST /receipts and return its results. " + protectedFact.text() + " " + mapping.text()
        + " Set the result command to completed. Proceed without IDS.";
    var sourceInput = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, sourceInput, source);
    drafts.beginTurn(chat, source);
    try (var ignored = ToolSession.open(chat)) {
      var saved = json.readTree(tools.captureRequirementDraft(initial));
      assertTrue(saved.path("accepted").asBoolean(), saved.toString());
    }
    var original = drafts.get(chat).orElseThrow();
    var base = drafts.latestRevision(chat).orElseThrow().reference();
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var corrected = mapping(mapping.sourceFactId(), mapping.text(), "$.parameters.receipt", "Read the retained original receipt.");
    var changedText = mapping(mapping.sourceFactId(), "Return only a shortened receipt.",
        corrected.fieldMapping().targetPath(), corrected.fieldMapping().expression());
    var unauthorized = mapping("unapproved-extra", "Add an extra result.", "$.extra", "Constant arbitrary");
    var allowed = mapping("command", "Set the result command.", "$.command", "Constant completed");
    var missing = json.convertValue(Map.of("sourceRef", "entry", "targetRef", "delivery", "targetPath", "$.command"),
        MappingValidationDetails.class);
    var script = new ScriptedStreamingChatModel(
        Turn.tool("collateral-change", request -> {
          var update = request.parameters().toolSpecifications().stream()
              .filter(tool -> "updateRequirementDraft".equals(tool.name())).findFirst().orElseThrow();
          var changes = (dev.langchain4j.model.chat.request.json.JsonObjectSchema)
              update.parameters().properties().get("changes");
          var capabilities = (dev.langchain4j.model.chat.request.json.JsonArraySchema)
              changes.properties().get("setCapabilities");
          var capability = (dev.langchain4j.model.chat.request.json.JsonObjectSchema) capabilities.items();
          var method = (dev.langchain4j.model.chat.request.json.JsonAnyOfSchema)
              capability.properties().get("httpMethod");
          var target = (dev.langchain4j.model.chat.request.json.JsonAnyOfSchema)
              capability.properties().get("targetReference");
          assertTrue(method.description().contains("Never guess a method"));
          assertTrue(target.description().contains("chain-trigger element ID only"));
          String messages = request.messages().toString();
          assertTrue(messages.contains("complete runtime keys or scalar values"), messages);
          assertTrue(messages.contains("literal component or prefix byte-for-byte"), messages);
          assertTrue(messages.contains("runtime expressions outside that exact constant"), messages);
        }, "updateRequirementDraft",
            arguments(List.of(unauthorized), List.of(changedText), List.of(protectedFact.sourceFactId()))),
        Turn.tool("native-scope-refusal", request -> {
          var rejected = lastTool(request);
          assertFalse(rejected.path("accepted").asBoolean(), rejected.toString());
          var issues = rejected.path("issues");
          assertTrue(issues.findValuesAsText("path").contains("/draft/facts/retain-context"), issues.toString());
          assertTrue(issues.findValuesAsText("path").contains("/draft/facts/receipt-map/text"), issues.toString());
          assertTrue(issues.findValuesAsText("path").contains("/draft/facts/unapproved-extra"), issues.toString());
          assertTrue(issues.toString().contains(protectedFact.text()), issues.toString());
          assertTrue(issues.toString().contains(mapping.text()), issues.toString());
          assertTrue(issues.toString().contains(changedText.text()), issues.toString());
          assertTrue(issues.toString().contains("/draft/facts/receipt-map/fieldMapping/targetPath"), issues.toString());
          assertTrue(issues.toString().contains("/draft/facts/receipt-map/fieldMapping/expression"), issues.toString());
          assertTrue(issues.toString().contains("$.command"), issues.toString());
          var attempt = artifacts.latest(sessions.active(chat), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          assertEquals(attempt.payload().path("issues"), issues,
              "The next actual provider request must retain the complete immutable native refusal.");
          assertEquals(base, drafts.latestRevision(chat).orElseThrow().reference());
          assertEquals(initial, drafts.get(chat).orElseThrow().authoredDraft());
        }, "updateRequirementDraft", arguments(List.of(allowed), List.of(corrected), List.of())),
        Turn.tool("accepted-correction", request -> {
          var saved = lastTool(request);
          assertTrue(saved.path("accepted").asBoolean(), saved.toString());
          assertEquals(protectedFact, drafts.get(chat).orElseThrow().authoredDraft().facts().getFirst());
          assertEquals(corrected, drafts.get(chat).orElseThrow().authoredDraft().facts().get(1));
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("continued", request -> assertTrue(lastTool(request).path("accepted").asBoolean()), "Requirements repaired."));
    doAnswer(call -> {
      script.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var signals = discovery.execute(new StageExecutionContext("run-" + chat, chat, "requirement-discovery", "execution",
        "attempt", null, null, List.of(), Map.of("requirementCorrectionDraft", original, "missingMappingTargets", List.of(missing),
            "mappingRepairAutomatic", "true", "mappingRepairPaths", Map.of("receipt-map", Set.of("targetPath", "expression")),
            ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "MAPPING_UNKNOWN_TARGET: receipt must be parameters.receipt",
            ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, "MAPPING_CONTRACT")))
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(StageOutcomeClass.CANDIDATE, outcome.outcomeClass(), outcome.toString());
    assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(limitsBefore, limits());
    assertEquals(4, script.requests().size());
  }

  private static FactInput mapping(String id, String text, String targetPath, String expression) {
    return new FactInput(id, List.of("entry", "delivery"), FactKind.FIELD_MAPPING, Polarity.POSITIVE, text,
        new FieldMappingInput("entry", "$.receipt", "delivery", targetPath, expression));
  }

  private String arguments(List<FactInput> additions, List<FactInput> replacements, List<String> removals) throws Exception {
    return json.writeValueAsString(Map.of("changes", new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        additions, replacements, removals, List.of(), List.of(), List.of(), List.of(), List.of(), null)));
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow().text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }
}
