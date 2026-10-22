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
import dev.langchain4j.model.chat.request.ChatRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class StageQuestionRepairFeedbackProviderTest {
  private static final String SOURCE = "Create an external POST /question-feedback endpoint with accessControlType=NONE. "
      + "The company recommends RBAC; I have not chosen whether to keep NONE or use RBAC. "
      + "Ask using the normal policy choice. If I choose RBAC, use only the reviewer role. Proceed without IDS.";
  private static final String QUESTION = "Keep requested NONE or use recommended RBAC?";

  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject RequirementDiscoveryCapability discovery;
  @Inject PolicyChoiceSelection policyChoices;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void currentOuterQuestionFindingReachesTheFirstRepairRequestWithNonblankAuthorInput(boolean validReviewedSource) throws Exception {
    String chat = "question-repair-feedback-" + UUID.randomUUID();
    var input = conversations.archive().beginInput(chat, SOURCE, null);
    conversations.archive().resolveInput(chat, input, SOURCE);
    drafts.beginTurn(chat, SOURCE);
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Create the external POST /question-feedback endpoint with accessControlType=NONE.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "true"),
                new PlanProperty("accessControlType", "NONE")), List.of()));
    var policy = new FactInput("access-policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "The company recommends RBAC for requested NONE; the author has not chosen an option.", null,
        new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE", "RBAC",
            "security-choice", null), null);
    var initial = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /question-feedback", null, null, null)), List.of()), List.of(endpoint, policy),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/question-feedback", null, null, null)), List.of(new QuestionInput("security-choice", List.of("entry"), QUESTION)),
        new DraftSettings(false, null));
    try (var ignored = ToolSession.open(chat)) {
      var captured = json.readTree(tools.captureRequirementDraft(initial));
      assertTrue(captured.path("accepted").asBoolean(), captured.toString());
    }
    var originalReference = drafts.latestRevision(chat).orElseThrow().reference();
    JsonNode originalSource = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var first = new ScriptedStreamingChatModel(Turn.text("initial-question", request -> {}, QUESTION));
    StageOutcome failure = execute(chat, Map.of("userText", SOURCE), first);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, failure.outcomeClass(), failure.toString());
    var cause = failure.recoveryCause();
    assertEquals(1, cause.findings().size());
    var finding = cause.findings().getFirst();
    assertEquals("AUTHOR_QUESTION_NOT_SUPPORTED", finding.code());
    assertEquals("/questions[]/security-choice", finding.fieldPointer());
    assertTrue(finding.message().contains(input.turnId()));
    assertEquals(originalReference, drafts.latestRevision(chat).orElseThrow().reference());

    var question = new QuestionInput("security-choice", List.of("entry"), QUESTION,
        new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
            List.of(validReviewedSource ? input.turnId() : "foreign-author-source")));
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(question), List.of(), null);
    var expected = new DraftInput(initial.flow(), initial.facts(), initial.capabilities(), List.of(question), initial.settings());
    String causeJson = json.writeValueAsString(cause);
    var attributes = new LinkedHashMap<String, Object>();
    attributes.put("userText", SOURCE);
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, failure.message());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, failure.outcomeClass().name());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "requirement-discovery");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, cause.formattedFindings());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, cause.causeCode().name());
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR, causeJson);
    var repair = new ScriptedStreamingChatModel(
        Turn.tool("repair-question-evidence", request -> {
          String packet = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
              .map(UserMessage::singleText).reduce("", (left, right) -> left + "\n" + right);
          assertTrue(packet.contains(SOURCE), "The complete original author input must remain available.");
          assertTrue(packet.contains("<current-native-repair-evidence>"));
          assertTrue(packet.contains("not an author message or amendment authority"));
          assertTrue(packet.contains(causeJson), "The entire actual current typed cause must reach the first provider request.");
          assertTrue(packet.contains(finding.code()));
          assertTrue(packet.contains(finding.fieldPointer()));
          assertTrue(packet.contains(finding.message()));
          assertTrue(packet.contains(input.turnId()));
          assertEquals(originalSource, json.valueToTree(conversations.archive().read(chat)));
          assertEquals(SOURCE, drafts.currentAuthorText(chat).orElseThrow());
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", update))),
        Turn.text("question-result", request -> {
          JsonNode result = lastTool(request);
          assertTrue(result.path("accepted").asBoolean(), result.toString());
          assertEquals(json.valueToTree(expected), result.path("draft"));
          assertEquals(expected, drafts.get(chat).orElseThrow().authoredDraft());
        }, QUESTION));
    StageOutcome repaired = execute(chat, attributes, repair);
    assertEquals(2, repair.requests().size(), "The projection must not add another model request.");
    assertEquals(originalSource, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(SOURCE, drafts.currentAuthorText(chat).orElseThrow());
    assertEquals(initial.facts(), drafts.get(chat).orElseThrow().authoredDraft().facts());
    assertEquals(initial.flow(), drafts.get(chat).orElseThrow().authoredDraft().flow());
    assertTrue(policyChoices.trusted(chat).isEmpty(), "Diagnostics and question evidence must not select an option.");
    if (validReviewedSource) {
      assertEquals(StageOutcomeClass.NEEDS_INPUT, repaired.outcomeClass(), repaired.toString());
      assertEquals(PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(repaired.message()).orElseThrow());
      assertTrue(drafts.validateAuthorQuestions(chat).isEmpty());
      var offered = policyChoices.pending(chat, 42).orElseThrow();
      assertEquals(policy, offered.fact());
      assertEquals(question, offered.question());
      assertEquals(drafts.latestRevision(chat).orElseThrow().reference(), offered.draftReference());
      assertEquals(input.generation(), offered.sourceGeneration());
      assertEquals(input.turnId(), offered.sourceTurnId());
    } else {
      assertEquals(StageOutcomeClass.CONTRACT_FAILURE, repaired.outcomeClass(), repaired.toString());
      assertTrue(repaired.recoveryCause().findings().stream().anyMatch(value -> "AUTHOR_QUESTION_NOT_SUPPORTED".equals(value.code())));
      assertFalse(drafts.validateAuthorQuestions(chat).isEmpty());
      assertTrue(artifacts.history(sessions.current(chat).orElseThrow().compilationId(), Kind.REQUIREMENT_POLICY_CHOICE).isEmpty());
    }
    assertEquals(limitsBefore, limits());
  }

  private StageOutcome execute(String chat, Map<String, Object> attributes, ScriptedStreamingChatModel script) {
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      script.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var signals = discovery.execute(new StageExecutionContext(chat + "-run", chat, "requirement-discovery",
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null, List.of(), attributes))
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    return signals.stream().filter(CapabilitySignal.Completed.class::isInstance).map(CapabilitySignal.Completed.class::cast)
        .findFirst().orElseThrow().outcome();
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      assertEquals("updateRequirementDraft", result.toolName());
      return json.readTree(result.text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }
}
