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
import org.junit.jupiter.api.Test;
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

  @Test
  void duplicateInteractionFeedbackKeepsTheQuestionCorrectionAtomicAndAllowsItsFocusedRepair() throws Exception {
    String chat = "duplicate-interaction-feedback-" + UUID.randomUUID();
    String source = "Create an external POST /review-status endpoint with accessControlType=NONE. "
        + "Company policy recommends RBAC. I have not chosen between NONE and RBAC. Show this choice; "
        + "if I choose RBAC, use only audit-reader. Proceed without IDS.";
    String questionText = "Keep NONE or apply recommended RBAC?";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    var ingress = new InteractionInput("ingress", Direction.INBOUND, "Portal", "POST /review-status", null, null, null);
    var endpoint = new FactInput("status-endpoint", List.of("ingress"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Create the external POST /review-status endpoint with accessControlType=NONE.", null, null,
        new RequirementElementIntent("ingress", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "ingress", null, null, null, List.of(new PlanProperty("externalRoute", "true"),
                new PlanProperty("accessControlType", "NONE")), List.of()));
    var policy = new FactInput("route-policy", List.of("ingress"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Company policy recommends RBAC; the author has not chosen between NONE and RBAC.", null,
        new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE", "RBAC",
            "policy-review", null), null);
    var role = new FactInput("conditional-role", List.of("ingress"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "If the author chooses RBAC, use only audit-reader.");
    var originalQuestion = new QuestionInput("policy-review", List.of("ingress"), questionText);
    var reviewedQuestion = new QuestionInput("policy-review", List.of("ingress"), questionText,
        new AuthorQuestionEvidence("ingress", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
            List.of(input.turnId())));
    var initial = new DraftInput(new FlowInput(List.of(ingress), List.of()), List.of(endpoint, policy, role),
        List.of(new CapabilityInput("ingress", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/review-status", null, null, null)), List.of(originalQuestion), new DraftSettings(false, null));
    try (var ignored = ToolSession.open(chat)) {
      var captured = json.readTree(tools.captureRequirementDraft(initial));
      assertTrue(captured.path("accepted").asBoolean(), captured.toString());
    }
    var base = drafts.latestRevision(chat).orElseThrow().reference();
    JsonNode sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var repeated = new InteractionInput("ingress", Direction.INBOUND, "Different portal", ingress.operation(), null, null, null);
    var rejectedUpdate = new DraftUpdate(List.of(repeated), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(reviewedQuestion), List.of(), null);
    var expected = new DraftInput(initial.flow(), initial.facts(), initial.capabilities(), List.of(reviewedQuestion), initial.settings());
    var script = new ScriptedStreamingChatModel(
        Turn.tool("duplicate-interaction-and-question", request -> {}, "updateRequirementDraft",
            json.writeValueAsString(Map.of("changes", rejectedUpdate))),
        Turn.tool("correct-question-without-readding-interaction", request -> {
          String packet = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
              .map(UserMessage::singleText).reduce("", (left, right) -> left + "\n" + right);
          assertTrue(packet.contains(source));
          assertTrue(packet.contains(input.turnId()));
          JsonNode result = lastTool(request);
          assertFalse(result.path("accepted").asBoolean(), result.toString());
          assertFalse(result.path("changed").asBoolean());
          assertEquals(json.valueToTree(base), result.path("revision"));
          assertEquals(json.valueToTree(initial), result.path("draft"));
          var issue = result.path("issues").get(0);
          assertEquals("DUPLICATE_ENTITY", issue.path("code").asText());
          assertEquals("/changes/addInteractions/0", issue.path("path").asText());
          assertEquals("ingress", issue.path("entityId").asText());
          String message = issue.path("message").asText();
          assertTrue(message.startsWith("Rejected addition identity=ingress for interactions:"
              + " this identity already exists in the current draft."), message);
          assertTrue(message.contains("Current interactions identities at this edit step: [ingress]"), message);
          assertTrue(message.contains("Use updateInteractions to change an existing identity, or omit it when unchanged."), message);
          var immutable = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          assertEquals(result.path("issues"), immutable.payload().path("issues"),
              "The first following Gather request must carry every immutable native issue without alteration.");
          assertEquals(json.valueToTree(rejectedUpdate), immutable.payload().path("arguments"));
          assertEquals(json.valueToTree(base), immutable.payload().path("acceptedDraftReference"));
          assertEquals(input.generation(), immutable.payload().path("sourceGeneration").asText());
          assertEquals(input.turnId(), immutable.payload().path("sourceTurnId").asText());
          assertEquals(immutable, artifacts.get(immutable.compilationId(), immutable.reference()).orElseThrow());
          assertEquals(base, drafts.latestRevision(chat).orElseThrow().reference());
          assertEquals(initial, drafts.get(chat).orElseThrow().authoredDraft());
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes",
            questionChanges(List.of(), List.of(reviewedQuestion), List.of())))),
        Turn.tool("stay-for-policy-review", request -> {
          var result = lastTool(request);
          assertTrue(result.path("accepted").asBoolean(), result.toString());
          assertTrue(result.path("changed").asBoolean());
          assertEquals(json.valueToTree(expected), result.path("draft"));
          assertTrue(drafts.validateAuthorQuestions(chat).isEmpty());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("offer-the-existing-policy-choice", request -> {
          assertTrue(lastTool(request, "finishRequirementDiscoveryTurn").path("accepted").asBoolean());
        }, questionText));
    var outcome = execute(chat, Map.of("userText", source), script);
    assertEquals(4, script.requests().size());
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass(), outcome.toString());
    assertEquals(PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(outcome.message()).orElseThrow());
    assertEquals(RequirementDiscoveryDirective.STAY, drafts.turnDirective(chat));
    assertEquals(expected, drafts.get(chat).orElseThrow().authoredDraft());
    assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(source, drafts.currentAuthorText(chat).orElseThrow());
    assertTrue(policyChoices.trusted(chat).isEmpty());
    var offered = policyChoices.pending(chat, 42).orElseThrow();
    assertEquals(policy, offered.fact());
    assertEquals(reviewedQuestion, offered.question());
    assertEquals(drafts.latestRevision(chat).orElseThrow().reference(), offered.draftReference());
    assertEquals(input.generation(), offered.sourceGeneration());
    assertEquals(input.turnId(), offered.sourceTurnId());
    assertEquals(limitsBefore, limits());
  }

  @Test
  void deletedQuestionUpdateReportsItsAbsentIdentityBeforeTheRequiredQuestionIsAddedAgain() throws Exception {
    String chat = "deleted-question-feedback-" + UUID.randomUUID();
    String source = SOURCE + " Add one required HTTP header, but I have not chosen its name. Ask for that name.";
    String headerQuestion = "What is the required HTTP header name?";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Create the external POST /question-feedback endpoint with accessControlType=NONE.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "true"),
                new PlanProperty("accessControlType", "NONE")), List.of()));
    var policy = new FactInput("access-policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "The company recommends RBAC for requested NONE; the author has not chosen an option.", null,
        new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE", "RBAC",
            "security-choice", null), null);
    var header = new FactInput("required-header", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Add one required HTTP header; the author has not chosen its name.");
    var security = new QuestionInput("security-choice", List.of("entry"), QUESTION,
        new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
            List.of(input.turnId())));
    var missingHeader = new QuestionInput("header-name", List.of("entry"), headerQuestion,
        new AuthorQuestionEvidence("entry", "headerName", AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of(input.turnId())));
    var initial = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /question-feedback", null, null, null)), List.of()), List.of(endpoint, policy, header),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/question-feedback", null, null, null)), List.of(security, missingHeader), new DraftSettings(false, null));
    try (var ignored = ToolSession.open(chat)) {
      var captured = json.readTree(tools.captureRequirementDraft(initial));
      assertTrue(captured.path("accepted").asBoolean(), captured.toString());
      var removed = json.readTree(tools.updateRequirementDraft(questionChanges(List.of(), List.of(), List.of("header-name"))));
      assertTrue(removed.path("accepted").asBoolean(), removed.toString());
      assertEquals(json.valueToTree(List.of(security)), removed.path("draft").path("openQuestions"));
    }
    var currentReference = drafts.latestRevision(chat).orElseThrow().reference();
    JsonNode sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var rejectedUpdate = questionChanges(List.of(), List.of(missingHeader), List.of());
    var restored = new DraftInput(initial.flow(), initial.facts(), initial.capabilities(),
        List.of(security, missingHeader), initial.settings());
    var script = new ScriptedStreamingChatModel(
        Turn.tool("update-deleted-question", request -> {
          assertEquals(currentReference, drafts.latestRevision(chat).orElseThrow().reference());
          assertEquals(List.of(security), drafts.get(chat).orElseThrow().authoredDraft().openQuestions());
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", rejectedUpdate))),
        Turn.tool("add-required-question", request -> {
          String packet = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
              .map(UserMessage::singleText).reduce("", (left, right) -> left + "\n" + right);
          assertTrue(packet.contains(source), "The complete original author input must remain available.");
          assertTrue(packet.contains(input.turnId()), "The saved author source ID must remain available.");
          JsonNode result = lastTool(request);
          assertFalse(result.path("accepted").asBoolean(), result.toString());
          assertFalse(result.path("changed").asBoolean());
          assertEquals(json.valueToTree(currentReference), result.path("revision"));
          assertEquals(json.valueToTree(List.of(security)), result.path("draft").path("openQuestions"));
          var issue = result.path("issues").get(0);
          assertEquals("CONFLICTING_EDIT", issue.path("code").asText());
          assertEquals("/changes/updateQuestions/0", issue.path("path").asText());
          assertEquals("header-name", issue.path("entityId").asText());
          String message = issue.path("message").asText();
          assertTrue(message.startsWith("Rejected update identity=header-name for questions:"
              + " this identity is absent from the current draft."), message);
          assertTrue(message.contains("Current questions identities at this edit step: [security-choice]"), message);
          assertTrue(message.contains("Use addQuestions only if this question is still required."), message);
          assertTrue(message.contains("Keep unresolved choices open and preserve the accepted flow and facts."), message);
          var immutable = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          assertEquals(result.path("issues"), immutable.payload().path("issues"),
              "The entire actual native finding must reach the first following provider request.");
          assertEquals(json.valueToTree(rejectedUpdate), immutable.payload().path("arguments"));
          assertEquals(json.valueToTree(currentReference), immutable.payload().path("acceptedDraftReference"));
          assertEquals(input.generation(), immutable.payload().path("sourceGeneration").asText());
          assertEquals(input.turnId(), immutable.payload().path("sourceTurnId").asText());
          assertEquals(immutable, artifacts.get(immutable.compilationId(), immutable.reference()).orElseThrow());
          assertEquals(currentReference, drafts.latestRevision(chat).orElseThrow().reference());
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
          assertEquals(initial.facts(), drafts.get(chat).orElseThrow().authoredDraft().facts());
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes",
            questionChanges(List.of(missingHeader), List.of(), List.of())))),
        Turn.tool("stay-for-author-choices", request -> {
          var result = lastTool(request);
          assertTrue(result.path("accepted").asBoolean(), result.toString());
          assertTrue(result.path("changed").asBoolean());
          assertEquals(json.valueToTree(restored), result.path("draft"));
          assertTrue(drafts.validateAuthorQuestions(chat).isEmpty());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("normal-questions", request -> {
          assertTrue(lastTool(request, "finishRequirementDiscoveryTurn").path("accepted").asBoolean());
        }, QUESTION + "\n" + headerQuestion));
    var outcome = execute(chat, Map.of("userText", source), script);
    assertEquals(4, script.requests().size(), "No additional provider dispatch is needed for the diagnostic.");
    assertEquals(StageOutcomeClass.NEEDS_INPUT, outcome.outcomeClass(), outcome.toString());
    assertEquals(PipelineGates.STAGE_CLARIFICATION, PipelineGates.gateOf(outcome.message()).orElseThrow());
    assertEquals(RequirementDiscoveryDirective.STAY, drafts.turnDirective(chat));
    assertEquals(restored, drafts.get(chat).orElseThrow().authoredDraft());
    assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(source, drafts.currentAuthorText(chat).orElseThrow());
    assertTrue(policyChoices.trusted(chat).isEmpty(), "Neither the refusal nor the correction selects a policy option.");
    var offered = policyChoices.pending(chat, 42).orElseThrow();
    assertEquals(policy, offered.fact());
    assertEquals(security, offered.question());
    assertEquals(drafts.latestRevision(chat).orElseThrow().reference(), offered.draftReference());
    assertEquals(input.generation(), offered.sourceGeneration());
    assertEquals(input.turnId(), offered.sourceTurnId());
    assertEquals(limitsBefore, limits());
  }

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
    assertTrue(finding.message().contains("/draft/openQuestions/security-choice/evidence"));
    assertTrue(finding.message().contains("rejected value null"));
    assertTrue(finding.message().contains("[interactionId, propertyKey, kind, reviewedInputIds]"));
    assertTrue(finding.message().contains("changes.updateQuestions"));
    for (var kind : AuthorQuestionEvidence.Kind.values()) {
      assertTrue(finding.message().contains(kind.name()), finding.message());
    }
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
          assertTrue(packet.contains("changes.updateQuestions"));
          assertTrue(packet.contains("rejected value null"));
          for (var kind : AuthorQuestionEvidence.Kind.values()) {
            assertTrue(packet.contains(kind.name()), packet);
          }
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
    return lastTool(request, "updateRequirementDraft");
  }

  private JsonNode lastTool(ChatRequest request, String expectedTool) {
    try {
      var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      assertEquals(expectedTool, result.toolName());
      return json.readTree(result.text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private DraftUpdate questionChanges(List<QuestionInput> additions, List<QuestionInput> replacements, List<String> removals) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), additions, replacements, removals, null);
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }
}
