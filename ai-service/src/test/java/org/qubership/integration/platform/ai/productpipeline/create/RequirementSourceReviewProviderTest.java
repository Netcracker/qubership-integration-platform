package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.ProductDiscoveryAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.RequirementAnalysisCapture;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;

/** Exercises source review through the registered analysis agent and its native capture tool. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementSourceReviewProviderTest {
  private static final String ALGORITHM = "Keep active rows, group by sku, preserve source order, "
      + "sum quantity differences, and keep each distinct location in locations.";

  @Inject ObjectMapper json;
  @Inject ProductDiscoveryAgent agent;
  @Inject ConversationService conversations;
  @Inject CaptureSession captureSession;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject CaptureRepairRunner repairRunner;
  @Inject ChatMemorySanitizer sanitizer;
  @InjectMock RateLimitStreamingChatModel provider;

  @Test
  void actualAnalysisRequestRetainsConsumedSourceAndLaterChoiceWhileExcludingResetTurns() throws Exception {
    String chat = "analysis-source-" + UUID.randomUUID();
    String excluded = "Discarded source before reset: use anonymous public access.";
    append(chat, excluded);
    conversations.clearMessages(chat);
    var first = append(chat, ALGORITHM);
    conversations.archive().consume(chat, first.generation(), List.of(first.turnId()), "selected-draft");
    var later = append(chat, "Use internal access with RBAC. This replaces the earlier access choice.");
    var approved = RequirementFactFixtures.greetingsApprovedDraft();
    assertFalse(approved.planningText().contains(ALGORITHM));
    var script = new ScriptedStreamingChatModel(
        Turn.tool("capture-complete-review", request -> {
          String text = userText(request);
          assertTrue(text.contains(ALGORITHM), "The actual model request lost the original algorithm.");
          assertTrue(text.contains(first.turnId()), text);
          assertTrue(text.contains(later.turnId()), text);
          assertTrue(text.contains("This replaces the earlier access choice."), text);
          assertFalse(text.contains(excluded), text);
          assertTrue(text.indexOf(first.turnId()) < text.indexOf(later.turnId()), text);
        }, "captureRequirementBrief", json.writeValueAsString(Map.of("capture",
            new RequirementAnalysisCapture("Greetings", List.of("GET /greetings"), List.of(),
                "Return Hello world!", List.of(), List.of())))));

    StageOutcome outcome = execute(chat, script);

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass(), outcome.toString());
    assertEquals(approved.facts(), ((org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief)
        outcome.candidates().getFirst().payload()).facts());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void quotedOmissionStopsBriefCreationWithoutGrantingSourceRepairAuthority(boolean legacyWire) throws Exception {
    String chat = "analysis-source-omission-" + UUID.randomUUID();
    var first = append(chat, ALGORITHM);
    append(chat, "Use internal access with RBAC. Keep this later choice.");
    var before = json.valueToTree(conversations.archive().read(chat));
    var report = new RequirementAnalysisCapture.SourceOmission(first.turnId(), ALGORITHM,
        "The draft describes greetings and omits the grouping algorithm.");
    var wire = json.readTree(arguments(List.of(report)));
    if (legacyWire) {
      var omission = (com.fasterxml.jackson.databind.node.ObjectNode) wire.path("capture").path("sourceOmissions").get(0);
      omission.remove(List.of("kind", "sourceFactId"));
    }
    var script = new ScriptedStreamingChatModel(Turn.tool("report-omission", request -> {
      String text = userText(request);
      assertTrue(text.contains(ALGORITHM), text);
      assertTrue(text.contains("Later explicit amendments and confirmed"), text);
      var capture = (dev.langchain4j.model.chat.request.json.JsonObjectSchema) request.parameters()
          .toolSpecifications().stream().filter(tool -> "captureRequirementBrief".equals(tool.name()))
          .findFirst().orElseThrow().parameters().properties().get("capture");
      assertTrue(capture.required().contains("sourceOmissions"), capture.toString());
    }, "captureRequirementBrief", json.writeValueAsString(wire)));

    StageOutcome outcome = execute(chat, script);

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
    assertEquals(RecoveryCauseCode.MISSING_BRIEF_FACTS, outcome.recoveryCause().causeCode());
    assertTrue(outcome.recoveryCause().isKnownBriefDefect());
    assertTrue(outcome.candidates().isEmpty(), "A source report must not become an accepted brief.");
    assertTrue(captureSession.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, chat),
        org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief.class).isEmpty());
    var finding = outcome.recoveryCause().findings().getFirst();
    assertTrue(finding.message().contains(first.turnId()), finding.message());
    assertTrue(finding.message().contains(ALGORITHM), finding.message());
    assertTrue(finding.message().contains(report.explanation()), finding.message());
    assertTrue(outcome.recoveryCause().requestedFact().isEmpty());
    assertTrue(finding.fieldPointer().isEmpty());
    assertTrue(finding.originRecordId().isEmpty());
    assertEquals(before, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(1, script.requests().size(), "Reporting an omission must not add a model call.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"valid", "unknown-fact", "other-fact-quote"})
  void unsupportedDraftAdditionRetainsItsNativeOwnerAndPinnedSourceEvidence(String fault) throws Exception {
    String chat = "analysis-source-addition-" + UUID.randomUUID();
    String quote = "Return plain text from a QIP script element.";
    var source = append(chat, ALGORITHM + " " + quote);
    var input = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput(
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput(List.of(
            new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput(
                "greetings", RequirementFlow.Direction.INBOUND, "Caller", "GET /greetings", "Greeting", null, null)), List.of()),
        List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput(
            "script_behavior", List.of("greetings"),
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind.BEHAVIOR,
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity.POSITIVE,
            "Return plain text from a QIP script element using default Groovy.", null, null, null,
            new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput(source.turnId(), quote))),
        List.of(new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput(
            "greetings", "http-trigger",
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode.CUSTOM,
            org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod.GET,
            "/greetings", null, null, null)), List.of(),
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings(true, null, null));
    var selected = org.qubership.integration.platform.ai.plan.RequirementCaptureProjection.toDraft(
        input, null, List.of(), "1", "test");
    var report = new RequirementAnalysisCapture.SourceOmission(source.turnId(), quote,
        "The selected fact adds a language the author did not specify.",
        RequirementAnalysisCapture.SourceDiscrepancyKind.UNSUPPORTED_ADDITION, "script_behavior");
    var reports = new java.util.ArrayList<Turn>();
    if (!"valid".equals(fault)) {
      reports.add(Turn.tool("invalid-addition-target", request -> {}, "captureRequirementBrief",
          arguments(List.of(new RequirementAnalysisCapture.SourceOmission(source.turnId(),
              "other-fact-quote".equals(fault) ? ALGORITHM : quote, report.explanation(), report.kind(),
              "unknown-fact".equals(fault) ? "foreign-fact" : report.sourceFactId())))));
    }
    reports.add(Turn.tool("report-unsupported-addition", request -> {
      if (!"valid".equals(fault)) {
        String text = userText(request);
        assertTrue(text.contains("SOURCE_ADDITION_TARGET_MISMATCH"), text);
      }
    }, "captureRequirementBrief", arguments(List.of(report))));
    var script = new ScriptedStreamingChatModel(reports.toArray(Turn[]::new));

    StageOutcome outcome = execute(chat, script, selected);

    var finding = outcome.recoveryCause().findings().getFirst();
    assertEquals("script_behavior", finding.originRecordId());
    assertEquals("/facts/script_behavior/text", finding.fieldPointer());
    assertEquals("SOURCE_UNSUPPORTED_ADDITION", finding.code());
    assertEquals(org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(selected),
        outcome.recoveryCause().sourceReview().draftHash());
    assertEquals(org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        org.qubership.integration.platform.ai.plan.AuthorSourceEvidence.packet(conversations.archive().read(chat))),
        outcome.recoveryCause().sourceReview().sourceHash());
    assertTrue(outcome.candidates().isEmpty(), "Source review must not be accepted as a requirement brief.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"invented-quote", "foreign-turn", "reset-turn"})
  void invalidSourceReportReachesTheNextActualRequestAndCanBeCorrectedInTheSameAttempt(String fault)
      throws Exception {
    String chat = "analysis-source-invalid-" + UUID.randomUUID();
    var discarded = append(chat, "Withdrawn requirement: allow public access.");
    conversations.clearMessages(chat);
    var active = append(chat, ALGORITHM);
    var archiveBefore = json.valueToTree(conversations.archive().read(chat));
    var selected = RequirementFactFixtures.greetingsApprovedDraft();
    var inbound = selected.flow().interactions().getFirst();
    var flow = new RequirementFlow(List.of(inbound,
        new RequirementFlow.Interaction("invoke", RequirementFlow.Direction.OUTBOUND,
            "Inventory", "reserve", "Return invocation failures in the response.",
            ServiceCallFailureMode.INLINE_RESPONSE),
        new RequirementFlow.Interaction("deliver", RequirementFlow.Direction.OUTBOUND,
            "Notifications", "send", "Deliver the result.")),
        List.of(new RequirementFlow.Transition(inbound.interactionId(), "invoke"),
            new RequirementFlow.Transition("invoke", "deliver")));
    var approved = selected.withFlow(flow)
        .withBoundInteraction("invoke", new CatalogBindingHint("3", "invoke", "invoke", "reserve",
            "inventory", "group", "spec", "reserve", "http", "POST", "/reserve", "catalog", Instant.EPOCH, "test"))
        .withBoundInteraction("deliver", new CatalogBindingHint("3", "deliver", "deliver", "send",
            "notifications", "group", "spec", "send", "http", "POST", "/send", "catalog", Instant.EPOCH, "test"));
    assertTrue(approved.readyForPlan(), approved.readinessError().orElse(""));
    String turn = fault.equals("foreign-turn") ? "not-in-this-invocation"
        : fault.equals("reset-turn") ? discarded.turnId() : active.turnId();
    String quote = fault.equals("invented-quote") ? "An algorithm never supplied by the author."
        : fault.equals("reset-turn") ? "Withdrawn requirement: allow public access." : ALGORITHM;
    String code = fault.equals("invented-quote") ? "SOURCE_QUOTE_MISMATCH" : "SOURCE_TURN_NOT_FOUND";
    var script = new ScriptedStreamingChatModel(
        Turn.tool("invalid-source-report", request -> assertSelectedModes(request, flow), "captureRequirementBrief",
            arguments(List.of(new RequirementAnalysisCapture.SourceOmission(turn, quote, "Report an omission.")))),
        Turn.tool("correct-source-report", request -> {
          assertSelectedModes(request, flow);
          String text = userText(request);
          assertTrue(text.contains(code), text);
          assertTrue(text.contains(ALGORITHM), text);
          assertTrue(text.contains("Attempt: attempt"), text);
          var packet = authorPacket(text);
          assertEquals(List.of(active.turnId()), java.util.stream.StreamSupport.stream(
              packet.path("inputs").spliterator(), false).map(input -> input.path("sourceTurnId").asText()).toList());
          assertNull(ProductCapabilityCaptureContext.binding(chat).orElseThrow().briefCandidate().get());
        }, "captureRequirementBrief", arguments(List.of(new RequirementAnalysisCapture.SourceOmission(
            active.turnId(), ALGORITHM, "The selected draft omits the source algorithm.")))));

    StageOutcome outcome = execute(chat, script, approved);

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
    assertEquals(RecoveryCauseCode.MISSING_BRIEF_FACTS, outcome.recoveryCause().causeCode());
    assertTrue(outcome.candidates().isEmpty());
    assertEquals(2, script.requests().size());
    assertTrue(outcome.recoveryCause().formattedFindings().contains(active.turnId()));
    assertTrue(outcome.recoveryCause().findings().stream()
        .allMatch(finding -> finding.fieldPointer().isEmpty() && finding.originRecordId().isEmpty()));
    assertEquals(archiveBefore, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(flow, approved.flow());
  }

  private String arguments(List<RequirementAnalysisCapture.SourceOmission> omissions) throws Exception {
    return json.writeValueAsString(Map.of("capture", new RequirementAnalysisCapture("Greetings",
        List.of("GET /greetings"), List.of(), "Return Hello world!", List.of(), List.of(), omissions)));
  }

  private org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Input append(
      String chat, String text) {
    var input = conversations.archive().beginInput(chat, text, null);
    conversations.archive().resolveInput(chat, input, text);
    return input;
  }

  private StageOutcome execute(String chat, ScriptedStreamingChatModel script) {
    return execute(chat, script, RequirementFactFixtures.greetingsApprovedDraft());
  }

  private StageOutcome execute(String chat, ScriptedStreamingChatModel script, RequirementDraft approved) {
    doAnswer(call -> {
      script.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var knowledge = FakeKnowledgeClient.defaultFixture();
    var capability = new RequirementAnalysisCapability(knowledge, knowledge, captureSession, feedback,
        agent, null, null, repairRunner, sanitizer, null, null, conversations);
    var signals = capability.execute(new StageExecutionContext(chat + "-run", chat, "requirement-analysis",
        "execution", "attempt", null, null, List.of(), Map.of("approvedDraft", approved)))
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    assertFalse(script.requests().isEmpty(), "The registered analysis agent did not call the provider: " + signals);
    assertTrue(userText(script.requests().getFirst()).contains(ALGORITHM),
        "The registered ProductDiscoveryAgent request lost the original source algorithm.");
    script.assertComplete();
    return signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
  }

  private static void assertSelectedModes(ChatRequest request, RequirementFlow flow) {
    String text = userText(request);
    for (var interaction : flow.interactions()) {
      assertTrue(text.contains("interactionId=" + interaction.interactionId()
          + " direction=" + interaction.direction()
          + " participant=" + interaction.participant()
          + " operation=" + interaction.operation()
          + " failureMode=" + interaction.failureMode()), text);
    }
  }

  private static String userText(ChatRequest request) {
    return request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .map(UserMessage::singleText).collect(java.util.stream.Collectors.joining("\n"));
  }

  private com.fasterxml.jackson.databind.JsonNode authorPacket(String text) {
    int start = text.indexOf("{\"generation\":");
    assertTrue(start >= 0, text);
    int end = text.indexOf('\n', start);
    try {
      return json.readTree(text.substring(start, end < 0 ? text.length() : end));
    } catch (java.io.IOException failure) {
      throw new AssertionError("The actual author evidence packet is not JSON.", failure);
    }
  }
}
