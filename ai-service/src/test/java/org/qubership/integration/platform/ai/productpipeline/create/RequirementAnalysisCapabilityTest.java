package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementBriefCapture;
import org.qubership.integration.platform.ai.plan.RequirementBriefTool;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class RequirementAnalysisCapabilityTest {

  @Test
  void greetingsBriefPreservesEverySourceFactIdExactlyOnce() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementBrief brief = coveringBrief(approved, "Greetings HTTP script");

    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge, knowledge, new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(), ctx -> brief);

    CapabilitySignal.Completed completed = run(capability, approved);
    // Capability itself returns SUCCEEDED when profile has no approval gate (create-plan /
    // unit tests). create-chain profile gates on brief approval via CANDIDATE.
    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    RequirementBrief candidate =
        (RequirementBrief) completed.outcome().candidates().get(0).payload();
    assertEquals(approved.facts().size(), candidate.facts().size());
    assertEquals(
        approved.facts().stream().map(RequirementFact::sourceFactId).sorted().toList(),
        candidate.facts().stream().map(RequirementFact::sourceFactId).sorted().toList());
    assertEquals(
        13,
        candidate.facts().stream()
            .filter(fact -> fact.polarity() == RequirementFactPolarity.NEGATIVE)
            .count());
  }

  @Test
  void langRouterBriefPreservesRouteAndSixExclusions() {
    RequirementDraft approved = RequirementFactFixtures.langRouterApprovedDraft();
    RequirementBrief brief = coveringBrief(approved, "LangRouter preferredLang routing");

    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge, knowledge, new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(), ctx -> brief);

    CapabilitySignal.Completed completed = run(capability, approved);
    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    RequirementBrief candidate =
        (RequirementBrief) completed.outcome().candidates().get(0).payload();
    assertTrue(
        candidate.facts().stream().anyMatch(fact -> fact.text().contains("/lang-router")
            || fact.text().contains("GET /lang-router")));
    assertEquals(
        6,
        candidate.facts().stream()
            .filter(fact -> fact.polarity() == RequirementFactPolarity.NEGATIVE)
            .count());
  }

  @Test
  void prefersPostImportDraftFromStoreOverStaleNeedsInputAttribute() {
    RequirementDraft staleDiscovery =
        new RequirementDraft(
            false,
            "stale discovery draft",
            DraftDecision.NEEDS_INPUT,
            List.of("What response format should the chain return?"),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            RequirementFactFixtures.greetingsApprovedDraft().facts(),
            false);
    RequirementDraft postImport =
        staleDiscovery.withCatalogBinding(
            new org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding(
                "sys-1", "spec-1", "group-1", "op-1"));
    assertTrue(postImport.readyForPlan());
    assertFalse(staleDiscovery.readyForPlan());

    RequirementDraftStore store = new RequirementDraftStore();
    store.put("conv-import", postImport);
    RequirementBrief brief = coveringBrief(postImport, "Greetings after import");

    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge,
            knowledge,
            new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(),
            null,
            null,
            null,
            ctx -> brief,
            null,
            null,
            store);

    // Attribute still holds the pre-import NEEDS_INPUT snapshot; store has READY_FOR_PLAN.
    CapabilitySignal.Completed completed =
        runWithUserText(capability, staleDiscovery, "conv-import", null);
    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
  }

  @Test
  void analysisRejectsBriefMissingSourceFact() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    List<RequirementFact> incomplete = approved.facts().subList(0, approved.facts().size() - 1);
    RequirementBrief brief =
        new RequirementBrief(
            "Greetings",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "incomplete",
            "draft-ref",
            approved.planningText(),
            incomplete);

    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge, knowledge, new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(), ctx -> brief);

    CapabilitySignal.Completed completed = run(capability, approved);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().contains("coverage failed"));
  }

  @Test
  void analysisSeedsWorkspaceWithApprovedDraftPlanningTextOnly() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementBrief brief = coveringBrief(approved, "Greetings");
    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge, knowledge, new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(), ctx -> brief);

    CapabilitySignal.Completed completed = run(capability, approved);
    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(
        approved.planningText(),
        RequirementAnalysisCapability.seedWorkspaceWithApprovedDraftOnly(approved)
            .get(org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType.RAW_USER_REQUEST)
            .map(
                a ->
                    ((org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload
                                .RawUserRequestPayload)
                            a.payload())
                        .effectiveText())
            .orElseThrow());
  }

  @Test
  void buildAnalysisUserMessageIncludesChangeRequestText() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    String message =
        RequirementAnalysisCapability.buildAnalysisUserMessage(approved, "add quartz scheduler");
    assertTrue(message.contains("Change request for this analysis turn:"));
    assertTrue(message.contains("add quartz scheduler"));
    assertTrue(message.contains(approved.planningText()));
    assertTrue(message.contains("summarize in pinned response locale en"));
    assertTrue(message.contains("do not infer another language from Planning text"));
  }

  @Test
  void buildAnalysisUserMessageDoesNotAskToInventMappingsWithoutServiceCallFacts() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    String message = RequirementAnalysisCapability.buildAnalysisUserMessage(approved);

    assertTrue(message.contains("dataMappings"));
    assertTrue(message.contains("Leave dataMappings empty"));
    assertFalse(message.contains("Capture typed dataMappings"), message);
  }

  @Test
  void buildAnalysisUserMessageAsksForMappingsWhenServiceCallFactsExist() {
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Create order via HTTP POST /orders then call Inventory",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.ENDPOINT,
                    "http-trigger",
                    "HTTP POST /orders"),
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.SERVICE_CALL,
                    "http-service-call",
                    "Inventory API: reserve stock")));
    String message = RequirementAnalysisCapability.buildAnalysisUserMessage(approved);

    assertTrue(message.contains("Capture typed dataMappings"));
    assertTrue(message.contains("PASS_THROUGH"));
    assertTrue(message.contains("EXPLICIT"));
  }

  @Test
  void runAnalyzerEscapesBracesInUserMessageForAiService() {
    String jsonBody = "{\"message\":\"hello world!\"}";
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Return JSON " + jsonBody + " from script",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CONSTRAINT,
                    "response",
                    "body must be " + jsonBody)));
    AtomicReference<String> lastUserMessage = new AtomicReference<>();
    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability analyzerOnly =
        new RequirementAnalysisCapability(
            knowledge,
            knowledge,
            new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(),
            null,
            null,
            null,
            null,
            (conversationId, userMessage) -> {
              lastUserMessage.set(userMessage);
              org.jboss.logmanager.MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
              return Multi.createFrom().item(ChatEvent.token("ok"));
            },
            null);

    runWithUserText(analyzerOnly, approved, "conv-escape", null);
    String escaped = lastUserMessage.get();
    assertTrue(escaped != null && !escaped.isBlank());
    assertTrue(escaped.contains("\\{\"message\":\"hello world!\"}"), escaped);
    assertFalse(escaped.contains(" JSON {\"message\""), escaped);
  }

  @Test
  void reExecutionClearsRequirementBriefSoSecondCaptureSucceeds() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    CaptureSession captureSession = new CaptureSession();
    CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore();
    RequirementBriefTool briefTool =
        new RequirementBriefTool(
            captureSession,
            new ObjectMapper(),
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));

    RequirementBrief firstBrief = coveringBrief(approved, "Greetings first");
    captureSession.accept(
        CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, "conv-analysis-rerun"),
        firstBrief,
        "first ok",
        "duplicate");

    AtomicInteger captures = new AtomicInteger();
    AtomicReference<String> lastUserMessage = new AtomicReference<>();
    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge,
            knowledge,
            new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(),
            captureSession,
            feedbackStore,
            null,
            null,
            (conversationId, userMessage) -> {
              lastUserMessage.set(userMessage);
              org.jboss.logmanager.MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
              String goal =
                  captures.incrementAndGet() == 1
                      ? "Greetings refined"
                      : "Greetings with quartz";
              String result =
                  briefTool.captureRequirementBrief(
                      new RequirementBriefCapture(
                          goal,
                          List.of(),
                          List.of(),
                          List.of(),
                          goal,
                          null,
                          approved.planningText(),
                          approved.facts(),
                          List.of()));
              assertTrue(result.contains("Requirement brief captured"), result);
              return Multi.createFrom().item(ChatEvent.token("ok"));
            },
            null);

    CapabilitySignal.Completed first =
        runWithUserText(capability, approved, "conv-analysis-rerun", "add quartz scheduler");
    assertEquals(StageOutcomeClass.SUCCEEDED, first.outcome().outcomeClass());
    RequirementBrief firstCandidate =
        (RequirementBrief) first.outcome().candidates().get(0).payload();
    assertEquals("Greetings refined", firstCandidate.goal());
    assertTrue(lastUserMessage.get().contains("add quartz scheduler"));

    CapabilitySignal.Completed second =
        runWithUserText(capability, approved, "conv-analysis-rerun", "also add retry");
    assertEquals(StageOutcomeClass.SUCCEEDED, second.outcome().outcomeClass());
    RequirementBrief secondCandidate =
        (RequirementBrief) second.outcome().candidates().get(0).payload();
    assertEquals("Greetings with quartz", secondCandidate.goal());
    assertEquals(2, captures.get());
    assertTrue(lastUserMessage.get().contains("also add retry"));
  }

  @Test
  void terminalValidationFailureRepairsMemoryAndRetriesAnalysisOnce() {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    CaptureSession captureSession = new CaptureSession();
    CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore();
    ChatMemorySanitizer sanitizer = mock(ChatMemorySanitizer.class);
    AppConfig appConfig = mock(AppConfig.class);
    AppConfig.CaptureConfig captureConfig = mock(AppConfig.CaptureConfig.class);
    when(appConfig.capture()).thenReturn(captureConfig);
    when(captureConfig.maxRepairAttempts()).thenReturn(1);
    CaptureRepairRunner repairRunner =
        new CaptureRepairRunner(
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)),
            feedbackStore,
            appConfig);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> repairMessage = new AtomicReference<>();
    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge,
            knowledge,
            new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(),
            captureSession,
            feedbackStore,
            null,
            null,
            (conversationId, userMessage) -> {
              if (calls.incrementAndGet() == 1) {
                String validation =
                    "Requirement brief coverage failed: INITIALIZATION mapping required. "
                        + "Set dataMappings to include the expected PASS_THROUGH mapping.";
                feedbackStore.recordPlanValidationFailure(
                    conversationId, validation, "unchanged invalid payload");
                return Multi.createFrom().failure(new CaptureValidationException(validation));
              }
              repairMessage.set(userMessage);
              captureSession.accept(
                  CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId),
                  coveringBrief(approved, "Repaired brief"),
                  "captured",
                  "duplicate");
              return Multi.createFrom().item(ChatEvent.token("repaired"));
            },
            null,
            null,
            repairRunner,
            sanitizer);

    CapabilitySignal.Completed completed =
        runWithUserText(capability, approved, "conv-terminal-repair", null);

    assertEquals(StageOutcomeClass.SUCCEEDED, completed.outcome().outcomeClass());
    assertEquals(2, calls.get());
    assertTrue(repairMessage.get().contains("INITIALIZATION mapping required"), repairMessage.get());
    verify(sanitizer)
        .repairDanglingToolCalls(
            "conv-terminal-repair",
            "Requirement brief coverage failed: INITIALIZATION mapping required. "
                + "Set dataMappings to include the expected PASS_THROUGH mapping.");
  }

  @Test
  void clearAnalysisCaptureStateRemovesPriorBriefAndFeedback() {
    CaptureSession captureSession = new CaptureSession();
    CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore();
    CaptureKey key = CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, "conv-clear");
    captureSession.accept(
        key,
        coveringBrief(RequirementFactFixtures.greetingsApprovedDraft(), "prior"),
        "ok",
        "dup");
    feedbackStore.recordPlanValidationFailure("conv-clear", "prior failure");

    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge,
            knowledge,
            new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(),
            captureSession,
            feedbackStore,
            null,
            null,
            null,
            null);
    capability.clearAnalysisCaptureState("conv-clear");

    assertFalse(captureSession.isPresent(key));
    assertTrue(feedbackStore.lastPlanFailure("conv-clear").isEmpty());
  }

  @Test
  void analysisEmitsRequirementAnalyzerSkillProgress() throws Exception {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementBrief brief = coveringBrief(approved, "Greetings HTTP script");

    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge,
            knowledge,
            new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(),
            ctx -> brief);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-analysis-skill",
            "conv-analysis-skill",
            "requirement-analysis",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            Map.of("approvedDraft", approved));
    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();

    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && RequirementAnalysisCapability.SKILL_ID.equals(sp.skillId())
                        && "running".equals(sp.status())));
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && RequirementAnalysisCapability.SKILL_ID.equals(sp.skillId())
                        && "completed".equals(sp.status())));
  }

  @Test
  void analysisWithApprovalGateEmitsCandidate() throws Exception {
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    RequirementBrief brief = coveringBrief(approved, "Greetings HTTP script");

    FakeKnowledgeClient knowledge = knowledgeWithMandatoryObjects();
    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            knowledge, knowledge, new org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator(), ctx -> brief);

    ProductPipelineProfile profile;
    try (java.io.InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
      profile = ProductPipelineProfileParser.parse(in);
    }

    StageExecutionContext context =
        new StageExecutionContext(
            "run-analysis",
            "conv-analysis",
            "requirement-analysis",
            "exec-1",
            "attempt-1",
            profile,
            null,
            List.of(),
            Map.of("approvedDraft", approved));
    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();
    CapabilitySignal.Completed completed =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(StageOutcomeClass.CANDIDATE, completed.outcome().outcomeClass());
  }

  private static CapabilitySignal.Completed run(
      RequirementAnalysisCapability capability, RequirementDraft approved) {
    return runWithUserText(capability, approved, "conv-analysis", null);
  }

  private static CapabilitySignal.Completed runWithUserText(
      RequirementAnalysisCapability capability,
      RequirementDraft approved,
      String conversationId,
      String userText) {
    Map<String, Object> attributes = new java.util.HashMap<>();
    attributes.put("approvedDraft", approved);
    if (userText != null) {
      attributes.put("userText", userText);
    }
    StageExecutionContext context =
        new StageExecutionContext(
            "run-analysis",
            conversationId,
            "requirement-analysis",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            attributes);
    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();
    return signals.stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected Completed signal, got " + signals));
  }

  private static RequirementBrief coveringBrief(RequirementDraft approved, String goal) {
    return new RequirementBrief(
        goal,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        goal,
        "approved-draft",
        approved.planningText(),
        approved.facts());
  }

  private static FakeKnowledgeClient knowledgeWithMandatoryObjects() {
    FakeKnowledgeClient client = FakeKnowledgeClient.defaultFixture();
    client.put(
        "CORPORATE_CIP_STANDARDS",
        "Standard",
        "CORPORATE_CIP_STANDARDS",
        "Corporate CIP standards.",
        List.of("CORPORATE_CIP_STANDARDS"));
    client.put(
        "pattern-standards",
        "Standard",
        "pattern-standards",
        "Pattern standards.",
        List.of("pattern-standards"));
    client.put(
        "element-standards",
        "Standard",
        "element-standards",
        "Element standards.",
        List.of("element-standards"));
    return client;
  }
}
