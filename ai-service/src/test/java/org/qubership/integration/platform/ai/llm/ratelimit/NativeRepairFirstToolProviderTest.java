package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureTools;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class NativeRepairFirstToolProviderTest {
  private static final String SOURCE = "Create an external POST /tool-protocol endpoint with accessControlType=NONE. "
      + "The company recommends RBAC; I have not chosen whether to keep NONE or use RBAC. "
      + "Ask using the normal policy choice. Proceed without IDS.";
  private static final String QUESTION = "Keep requested NONE or use recommended RBAC?";

  enum Response { CORRECT_EVIDENCE, FOREIGN_EVIDENCE, READ_ONLY, FINISH_ONLY, IGNORED_CHOICE, UNSUPPORTED_CHOICE }

  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject RequirementDiscoveryCapability discovery;
  @Inject PolicyChoiceSelection choices;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @ParameterizedTest
  @EnumSource(Response.class)
  void firstToolProtocolUsesTheRealGatherBoundaryAndLeavesNativeAdmissionInCharge(Response response) throws Exception {
    String chat = "native-first-tool-" + UUID.randomUUID();
    var input = conversations.archive().beginInput(chat, SOURCE, null);
    conversations.archive().resolveInput(chat, input, SOURCE);
    drafts.beginTurn(chat, SOURCE);
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Create external POST /tool-protocol with accessControlType=NONE.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "true"),
                new PlanProperty("accessControlType", "NONE")), List.of()));
    var policy = new FactInput("access-policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "The company recommends RBAC for requested NONE; the author has not chosen an option.", null,
        new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE", "RBAC",
            "security-choice", null), null);
    var initial = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /tool-protocol", null, null, null)), List.of()), List.of(endpoint, policy),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/tool-protocol", null, null, null)), List.of(new QuestionInput("security-choice", List.of("entry"), QUESTION)),
        new DraftSettings(false, null));
    try (var ignored = ToolSession.open(chat)) {
      var captured = json.readTree(tools.captureRequirementDraft(initial));
      assertTrue(captured.path("accepted").asBoolean(), captured.toString());
    }
    var originalReference = drafts.latestRevision(chat).orElseThrow().reference();
    JsonNode sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var ordinary = new ScriptedStreamingChatModel(Turn.text("normal-question", request ->
        assertNull(request.parameters().toolChoice(), "A normal gather request must stay unchanged."), QUESTION));
    StageOutcome first = execute(chat, Map.of("userText", SOURCE), ordinary);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, first.outcomeClass());
    assertTrue(first.recoveryCause().findings().stream()
        .anyMatch(finding -> "AUTHOR_QUESTION_NOT_SUPPORTED".equals(finding.code())));

    var question = new QuestionInput("security-choice", List.of("entry"), QUESTION,
        new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
            List.of(response == Response.FOREIGN_EVIDENCE ? "foreign-source" : input.turnId())));
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(question), List.of(), null);
    ScriptedStreamingChatModel repair;
    if (response == Response.IGNORED_CHOICE) {
      repair = new ScriptedStreamingChatModel(Turn.text("ignored-required", request ->
          assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice()), "I will repair the question."));
    } else if (response == Response.UNSUPPORTED_CHOICE) {
      repair = new ScriptedStreamingChatModel(Turn.failure("unsupported-required", request ->
          assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice()),
          new IllegalArgumentException("Unsupported tool choice")));
    } else if (response == Response.FINISH_ONLY) {
      repair = new ScriptedStreamingChatModel(
          Turn.tool("finish-cannot-replace-repair", request ->
              assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice()),
              "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
          Turn.text("finish-result", request -> {
            assertEquals(ToolChoice.AUTO, request.parameters().toolChoice());
            var refused = lastTool(request, "finishRequirementDiscoveryTurn");
            assertFalse(refused.path("accepted").asBoolean(), refused.toString());
            assertTrue(refused.path("issues").toString().contains("AUTHOR_QUESTION_NOT_SUPPORTED"));
          }, QUESTION));
    } else {
      Turn read = Turn.tool("read-before-repair", request ->
          assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice()), "readRequirementDraft", "{}");
      if (response == Response.READ_ONLY) {
        repair = new ScriptedStreamingChatModel(read, Turn.text("read-is-not-repair", request -> {
          assertEquals(ToolChoice.AUTO, request.parameters().toolChoice());
          assertEquals(json.valueToTree(initial), lastTool(request, "readRequirementDraft").path("draft"));
        }, "The draft still needs question evidence."));
      } else {
        repair = new ScriptedStreamingChatModel(read,
            Turn.tool("capture-question-evidence", request -> {
              assertEquals(ToolChoice.AUTO, request.parameters().toolChoice());
              assertEquals(json.valueToTree(initial), lastTool(request, "readRequirementDraft").path("draft"));
            }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", update))),
            Turn.text("native-result", request -> {
              assertEquals(ToolChoice.AUTO, request.parameters().toolChoice());
              var captured = lastTool(request, "updateRequirementDraft");
              assertTrue(captured.path("accepted").asBoolean(), captured.toString());
              assertEquals(json.valueToTree(initial.facts()), captured.path("draft").path("facts"));
            }, QUESTION));
      }
    }
    var cause = first.recoveryCause();
    var attributes = new LinkedHashMap<String, Object>();
    attributes.put("userText", SOURCE);
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, first.message());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, first.outcomeClass().name());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "requirement-discovery");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, cause.formattedFindings());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, cause.causeCode().name());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR, json.writeValueAsString(cause));
    StageOutcome repaired = execute(chat, attributes, repair);

    assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(initial.facts(), drafts.get(chat).orElseThrow().authoredDraft().facts());
    assertEquals(initial.flow(), drafts.get(chat).orElseThrow().authoredDraft().flow());
    assertTrue(choices.trusted(chat).isEmpty(), "A tool requirement cannot supply an author selection.");
    assertEquals(limitsBefore, limits());
    if (response == Response.CORRECT_EVIDENCE) {
      assertEquals(StageOutcomeClass.NEEDS_INPUT, repaired.outcomeClass(), repaired.toString());
      assertTrue(drafts.validateAuthorQuestions(chat).isEmpty());
      assertTrue(choices.pending(chat, 21).isPresent());
      assertEquals(3, repair.requests().size());
    } else if (response == Response.UNSUPPORTED_CHOICE || response == Response.IGNORED_CHOICE) {
      assertEquals(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, repaired.outcomeClass(), repaired.toString());
      assertEquals(1, repair.requests().size(), "A choice refusal must not add a protocol retry.");
      assertEquals(originalReference, drafts.latestRevision(chat).orElseThrow().reference());
      if (response == Response.IGNORED_CHOICE) {
        assertTrue(repaired.message().contains("without a tool action"));
      }
    } else {
      assertEquals(StageOutcomeClass.CONTRACT_FAILURE, repaired.outcomeClass(), repaired.toString());
      assertTrue(repaired.recoveryCause().findings().stream()
          .anyMatch(finding -> "AUTHOR_QUESTION_NOT_SUPPORTED".equals(finding.code())));
      assertFalse(drafts.validateAuthorQuestions(chat).isEmpty());
      if (response != Response.FOREIGN_EVIDENCE) {
        assertEquals(originalReference, drafts.latestRevision(chat).orElseThrow().reference());
      }
      assertEquals(response == Response.IGNORED_CHOICE ? 1
          : response == Response.READ_ONLY || response == Response.FINISH_ONLY ? 2 : 3,
          repair.requests().size());
    }
  }

  @Test
  void ignoredRequiredCannotProduceAReadyCardOrAdvanceAnAlreadyValidDraft() throws Exception {
    String chat = "ready-draft-required-" + UUID.randomUUID();
    String source = "Create an internal POST /ready-tool-protocol endpoint. Proceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        source, null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false")), List.of()));
    var initial = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /ready-tool-protocol", null, null, null)), List.of()), List.of(endpoint),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/ready-tool-protocol", null, null, null)), List.of(), new DraftSettings(false, null));
    try (var ignored = ToolSession.open(chat)) {
      var captured = json.readTree(tools.captureRequirementDraft(initial));
      assertTrue(captured.path("accepted").asBoolean(), captured.toString());
    }
    assertTrue(drafts.get(chat).orElseThrow().readyForPlan());
    var before = drafts.latestRevision(chat).orElseThrow().reference();
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var ordinaryFailure = new ScriptedStreamingChatModel(Turn.failure("ordinary-provider-failure", request ->
        assertNull(request.parameters().toolChoice()), new IllegalStateException("Provider request failed")));
    var failed = execute(chat, Map.of("userText", source), ordinaryFailure);
    assertEquals(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, failed.outcomeClass());
    var attributes = Map.<String, Object>of("userText", source,
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, failed.message(),
        ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, failed.outcomeClass().name(),
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "requirement-discovery");
    var ignored = new ScriptedStreamingChatModel(Turn.text("ignored-required-on-ready-draft", request ->
        assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice()), "The draft is ready. Continue to planning."));

    var refused = execute(chat, attributes, ignored);

    assertEquals(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, refused.outcomeClass(), refused.toString());
    assertTrue(refused.message().contains("without a tool action"));
    assertTrue(refused.candidates().isEmpty());
    assertEquals(1, ignored.requests().size());
    assertEquals(before, drafts.latestRevision(chat).orElseThrow().reference());
    assertEquals(initial, drafts.get(chat).orElseThrow().authoredDraft());
    assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(limitsBefore, limits());
    assertTrue(choices.trusted(chat).isEmpty());
  }

  private StageOutcome execute(String chat, Map<String, Object> attributes, ScriptedStreamingChatModel script) {
    var boundary = new RateLimitStreamingChatModel(script, config,
        seconds -> { throw new AssertionError("Unexpected physical retry"); },
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
    doAnswer(call -> {
      boundary.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var signals = discovery.execute(new StageExecutionContext(chat + "-run", chat, "requirement-discovery",
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null, List.of(), attributes))
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    return signals.stream().filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
        .findFirst().orElseThrow().outcome();
  }

  private JsonNode lastTool(ChatRequest request, String name) {
    try {
      var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      assertEquals(name, result.toolName());
      return json.readTree(result.text());
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }
}
