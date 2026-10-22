package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.nativeAnchorFact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureResult;
import org.qubership.integration.platform.ai.plan.RequirementCaptureTools;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementNoProgressDiagnosisProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject FailureNarrativeAgent agent;
  @Inject AppConfig config;
  @InjectMock RateLimitChatModel provider;

  @Test
  void firstDiagnosisReceivesEveryStoredNativeIssueIncludingTheCompleteNoProgressInstruction() throws Exception {
    String chat = "no-progress-diagnosis-" + UUID.randomUUID();
    String runId = "native-no-progress-" + UUID.randomUUID();
    String sourceText = "Accept private POST /receipts. Keep the receipt request context.";
    var source = conversations.archive().beginInput(chat, sourceText, null);
    conversations.archive().resolveInput(chat, source, sourceText);
    drafts.beginTurn(chat, sourceText);
    var base = new DraftInput(new FlowInput(List.of(new InteractionInput(
        "entry", Direction.INBOUND, "Caller", "POST /receipts", null, null, null)), List.of()),
        List.of(nativeAnchorFact("entry", "http-trigger"),
            new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, sourceText)),
        List.of(new CapabilityInput(
            "entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/receipts", null, null, null)),
        List.of(), new DraftSettings(null, null));
    var changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new FactInput("context", List.of("missing"), FactKind.BEHAVIOR, Polarity.POSITIVE,
            "Keep the receipt request context.")), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), null);
    CaptureNoProgressException stopped;
    try (var ignored = ToolSession.open(chat)) {
      var initial = json.readValue(tools.captureRequirementDraft(base), RequirementCaptureResult.class);
      assertTrue(initial.accepted(), initial.toString());
      assertFalse(json.readValue(tools.updateRequirementDraft(changes), RequirementCaptureResult.class).accepted());
      stopped = assertThrows(CaptureNoProgressException.class, () -> tools.updateRequirementDraft(changes));
    }
    var accepted = drafts.latestRevision(chat).orElseThrow();
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var creditsBefore = feedback.fingerprintStore().softFingerprints(chat);
    var rejected = drafts.rejectedCaptureRevision(chat).orElseThrow();
    var attempt = artifacts.payload(rejected, RequirementDraftStore.RequirementCaptureAttempt.class);
    attempt.issues().forEach(issue -> assertEquals(
        new RequirementCaptureResult.IssueDetails(null, List.of(), List.of()), issue.details()));
    var expectedIssues = attempt.issues().stream().map(issue -> new CaptureToolOutcomeGateway.TypedIssue(
        issue.code(), issue.path(), issue.message(), issue.entityId())).toList();
    assertTrue(expectedIssues.stream().anyMatch(issue -> "REPAIR_NO_PROGRESS".equals(issue.code())
        && issue.message().endsWith("Submit a different correction.")));
    var outcome = StageOutcome.invocationFailure(stopped);
    var cause = new ObjectMapper().readValue(ProductPipelineRunSupport.recoveryCauseJson(outcome.recoveryCause()),
        org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause.class);
    var evidence = new RecoveryEvidence(1, "native-refusal", cause.causeCode().name(), "requirement-discovery",
        "requirement-discovery", null, null, List.of(accepted.reference()), List.of(), null,
        List.of(), List.of(), List.of(), List.of(source.turnId()), false, cause.captureFailure());
    var context = new RecoveryContext(evidence, null, attempt.arguments(), "en");
    var requestCount = new AtomicInteger();
    var firstContext = new AtomicReference<JsonNode>();
    var upstream = mock(ChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
    when(upstream.chat(any(ChatRequest.class))).thenAnswer(call -> {
      requestCount.incrementAndGet();
      ChatRequest request = call.getArgument(0);
      String prompt = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
          .map(UserMessage::singleText).filter(text -> text.startsWith("You diagnose create-chain recovery"))
          .findFirst().orElseThrow();
      String startMarker = "\n\nContext: ";
      int start = prompt.indexOf(startMarker) + startMarker.length();
      int end = prompt.indexOf("\n\nReturn RecoveryDecision only.", start);
      assertTrue(start >= startMarker.length() && end > start, prompt);
      assertTrue(firstContext.compareAndSet(null, json.readTree(prompt.substring(start, end))),
          "Only the first diagnosis request is authorized by this test.");
      var reply = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, null, List.of(evidence.failureId()),
          RecoveryAction.PARK, List.of(), "", "Progress is saved.");
      return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(reply))).build();
    });
    var budget = new LlmRunBudget(new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore(),
        json, config);
    var boundary = new RateLimitChatModel(upstream, config, seconds -> {
      throw new AssertionError("Unexpected provider retry");
    }, new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    doAnswer(call -> boundary.chat(call.getArgument(0, ChatRequest.class))).when(provider).chat(any(ChatRequest.class));

    var result = new FailureNarrative(agent).diagnoseRecovery(runId, context);

    assertEquals(FailureNarrative.RecoveryStatus.READY, result.status());
    assertEquals(RecoveryAction.PARK, result.decision().action());
    assertEquals(1, requestCount.get());
    assertNotNull(firstContext.get());
    var delivered = firstContext.get().path("evidence").path("captureFailure");
    assertEquals(json.valueToTree(expectedIssues), delivered.path("feedback").path("issues"));
    assertEquals(json.valueToTree(rejected.reference()), delivered.path("rejectedCandidate"));
    assertEquals(json.valueToTree(accepted.reference()), delivered.path("acceptedBase"));
    assertEquals(json.valueToTree(stopped.evidence().feedback().evidenceReference()),
        delivered.path("feedback").path("evidenceReference"));
    assertEquals(CaptureFailureClass.IDENTICAL_SPAM.name(), delivered.path("feedback").path("failureClass").asText());
    assertTrue(delivered.path("feedback").path("noProgress").asBoolean());
    assertFalse(delivered.path("feedback").path("outerAllowed").asBoolean());
    assertEquals(accepted, drafts.latestRevision(chat).orElseThrow());
    assertEquals(base, drafts.get(chat).orElseThrow().authoredDraft());
    assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(creditsBefore, feedback.fingerprintStore().softFingerprints(chat));
    assertEquals(1, budget.state(runId).requests());
    assertEquals(0, budget.state(runId).grants());
    assertTrue(budget.state(runId).active().isEmpty());
  }
}
