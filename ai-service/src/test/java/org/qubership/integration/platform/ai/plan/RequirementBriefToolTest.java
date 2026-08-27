package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class RequirementBriefToolTest {

  private CaptureSession captureSession;
  private CaptureAttemptFeedbackStore feedbackStore;
  private RequirementBriefTool tool;

  @BeforeEach
  void setUp() {
    captureSession = new CaptureSession();
    feedbackStore = new CaptureAttemptFeedbackStore();
    tool =
        new RequirementBriefTool(
            captureSession,
            new ObjectMapper(),
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));
  }

  private Optional<RequirementBrief> getBrief(String conversationId) {
    return captureSession.get(
        CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId),
        RequirementBrief.class);
  }

  @Test
  void rejectsEmptyGoalAndSummary() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture("", java.util.List.of(), java.util.List.of(), java.util.List.of(), ""));

    assertTrue(result.contains("non-empty goal or summary"));
    assertFalse(getBrief("conv-brief").isPresent());
  }

  @Test
  void storesBriefWithGoal() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Greeting endpoint",
                java.util.List.of("HTTP body"),
                java.util.List.of(),
                java.util.List.of(),
                ""));

    assertTrue(result.contains("Requirement brief captured"));
    assertTrue(result.contains("finish this turn"));
    assertTrue(getBrief("conv-brief").isPresent());
    assertEquals("Greeting endpoint", getBrief("conv-brief").orElseThrow().goal());
  }

  @Test
  void storesApiHubIdentifiersInStructuredFields() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Call customer API",
                java.util.List.of("packageId: pkg-1", "operationId: getCustomer"),
                java.util.List.of("protocol: REST"),
                java.util.List.of("API Hub service not resolved"),
                ""));

    assertTrue(result.contains("Requirement brief captured"));
    var brief = getBrief("conv-brief").orElseThrow();
    assertTrue(brief.inputs().contains("packageId: pkg-1"));
    assertTrue(brief.inputs().contains("operationId: getCustomer"));
    assertTrue(brief.constraints().contains("protocol: REST"));
    assertTrue(brief.assumptions().contains("API Hub service not resolved"));
  }

  @Test
  void duplicateCaptureAfterBriefStoredThrowsTerminalSignal() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    tool.captureRequirementBrief(
        new RequirementBriefCapture(
            "Greeting endpoint", java.util.List.of(), java.util.List.of(), java.util.List.of(), ""));

    CaptureValidationException thrown =
        assertThrows(
            CaptureValidationException.class,
            () ->
                tool.captureRequirementBrief(
                    new RequirementBriefCapture(
                        "Greeting endpoint",
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of(),
                        "")));
    assertTrue(thrown.getMessage().contains("already captured"));
    assertTrue(thrown.getMessage().contains("finish this turn"));
    assertTrue(getBrief("conv-brief").isPresent());
  }

  @Test
  void pinsApprovedDraftTextWhenCaptureContainsParaphrase() {
    RequirementDraftStore draftStore = new RequirementDraftStore();
    RequirementDraft approved =
        org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures
            .greetingsApprovedDraft();
    draftStore.put("conv-brief", approved);
    tool =
        new RequirementBriefTool(
            captureSession,
            draftStore,
            new ObjectMapper(),
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));

    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Greeting endpoint",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "summary only",
                null,
                "paraphrased draft that is not the pinned planning text",
                approved.facts(),
                java.util.List.of()));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief stored = getBrief("conv-brief").orElseThrow();
    assertEquals(approved.planningText(), stored.approvedDraftText());
    assertTrue(feedbackStore.lastPlanFailure("conv-brief").isEmpty());
  }

  @Test
  void pinsMissingFactsFromApprovedDraftAndStoresBrief() {
    RequirementDraftStore draftStore = new RequirementDraftStore();
    RequirementDraft approved =
        org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures
            .greetingsApprovedDraft();
    draftStore.put("conv-brief", approved);
    tool =
        new RequirementBriefTool(
            captureSession,
            draftStore,
            new ObjectMapper(),
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));

    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Greeting endpoint",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "summary only",
                null,
                approved.planningText(),
                approved.facts().subList(0, 3),
                java.util.List.of()));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief stored = getBrief("conv-brief").orElseThrow();
    assertEquals(approved.facts().size(), stored.facts().size());
    assertEquals(
        approved.facts().stream().map(RequirementFact::sourceFactId).sorted().toList(),
        stored.facts().stream().map(RequirementFact::sourceFactId).sorted().toList());
  }

  @Test
  void captureValidationExceptionPropagatesOnStreamingPath() {
    assertTrue(
        new CaptureValidationException("x")
            instanceof io.quarkiverse.langchain4j.runtime.PreventsErrorHandlerExecution);
  }

  @Test
  void storesTypedDataMappingsWhenProvided() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    RequirementDataMapping initialization =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.customerId", null)),
            List.of("fact-init"));
    RequirementDataMapping conversion =
        new RequirementDataMapping(
            "map-conv",
            RequirementDataMapping.Stage.CONVERSION,
            "call-1",
            "call-2",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.a", "$.b", null)),
            List.of("fact-conv"));
    RequirementDataMapping response =
        new RequirementDataMapping(
            "map-resp",
            RequirementDataMapping.Stage.RESPONSE,
            "call-2",
            "trigger-1",
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of(),
            List.of("fact-resp"));

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Order flow",
                List.of(),
                List.of(),
                List.of(),
                "Two calls with mappings",
                null,
                null,
                List.of(),
                List.of(),
                List.of(initialization, conversion, response)));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief brief = getBrief("conv-brief").orElseThrow();
    assertEquals(List.of(initialization, conversion, response), brief.dataMappings());
    assertEquals(2, brief.mappingIntents().size());
    assertEquals("map-init", brief.mappingIntents().getFirst().mappingIntentId());
    assertEquals("map-conv", brief.mappingIntents().get(1).mappingIntentId());
    assertEquals(
        MappingRuleStatus.PROPOSED,
        brief.mappingIntents().getFirst().rules().getFirst().status());
  }

  @Test
  void identityOnlyAutoDoesNotCreateMappingIntent() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    RequirementDataMapping identity =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.id", null)),
            List.of("fact-init"));

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Pass-through order flow",
                List.of(),
                List.of(),
                List.of(),
                "Identity copy only",
                null,
                null,
                List.of(),
                List.of(),
                List.of(identity)));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief brief = getBrief("conv-brief").orElseThrow();
    assertEquals(List.of(identity), brief.dataMappings());
    assertTrue(brief.mappingIntents().isEmpty());
  }

  @Test
  void recordsEntryPointsFromCatalogTriggerCapability() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    RequirementFact kafka =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-trigger-2",
            "Consume user events",
            "",
            "consumeUserEvent",
            "user/events",
            "",
            "");
    RequirementFact call =
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "http-service-call",
            "Look up a pet",
            "Petstore Ext",
            "getPetById",
            "",
            "",
            "");

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Kafka pet lookup",
                List.of(),
                List.of(),
                List.of(),
                "Consume events and look up a pet",
                null,
                null,
                List.of(kafka, call),
                List.of(),
                List.of()));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief brief = getBrief("conv-brief").orElseThrow();
    assertEquals(1, brief.entryPoints().size());
    assertEquals("kafka-trigger-2", brief.entryPoints().getFirst().capabilityKey());
    assertEquals("user/events", brief.entryPoints().getFirst().topic());
    assertEquals(1, brief.serviceCalls().size());
    assertEquals("Petstore Ext", brief.serviceCalls().getFirst().participant());
    assertTrue(brief.mappingIntents().isEmpty());
    assertTrue(brief.dataMappings().isEmpty());
  }

  @Test
  void pinsApprovedServiceCallsInsteadOfAgentCopies() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    RequirementFact omFact =
        serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult");
    RequirementFact wfmFact =
        serviceCallFact("fact-wfm", "call-wfm-create-task", "Salesforce WFM", "createTask");
    CatalogBindingHint omHint =
        catalogHint(
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-om",
            observedAt);
    CatalogBindingHint wfmHint =
        catalogHint(
            "call-wfm-create-task",
            "fact-wfm",
            "createTask",
            "sys-wfm",
            "sg-wfm",
            "spec-wfm",
            "op-wfm",
            observedAt);
    RequirementServiceCall omCall =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", omHint);
    RequirementServiceCall wfmCall =
        new RequirementServiceCall(
            "call-wfm-create-task", "fact-wfm", "Salesforce WFM", "createTask", wfmHint);
    RequirementDraft approved =
        new RequirementDraft(
            true,
            "Call OM then Salesforce WFM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            "brainstorming",
            "1",
            null,
            null,
            null,
            false,
            List.of(omFact, wfmFact),
            false,
            null,
            List.of(omCall, wfmCall));
    RequirementDraftStore draftStore = new RequirementDraftStore();
    draftStore.put("conv-brief", approved);
    tool =
        new RequirementBriefTool(
            captureSession,
            draftStore,
            new ObjectMapper(),
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));

    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    RequirementServiceCall agentCopy =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", null);
    RequirementBrief agentBrief =
        new RequirementBrief(
                "Call OM then Salesforce WFM",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "summary only",
                null,
                "paraphrased draft that is not the pinned planning text",
                List.of(omFact),
                List.of())
            .withServiceCalls(List.of(agentCopy));
    RequirementBrief pinned = RequirementBriefTool.pinApprovedDraftFacts(agentBrief, approved);

    assertEquals(approved.facts(), pinned.facts());
    assertEquals(approved.planningText(), pinned.approvedDraftText());
    assertEquals(List.of(omCall, wfmCall), pinned.serviceCalls());
    assertEquals(omHint, pinned.serviceCalls().get(0).catalogBinding());
    assertEquals(wfmHint, pinned.serviceCalls().get(1).catalogBinding());

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Call OM then Salesforce WFM",
                List.of(),
                List.of(),
                List.of(),
                "summary only",
                null,
                "paraphrased draft that is not the pinned planning text",
                List.of(omFact),
                List.of()));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief stored = getBrief("conv-brief").orElseThrow();
    assertEquals(List.of(omCall, wfmCall), stored.serviceCalls());
    assertEquals(omHint, stored.serviceCalls().get(0).catalogBinding());
    assertEquals(wfmHint, stored.serviceCalls().get(1).catalogBinding());
  }

  @Test
  void toolDescriptionLeavesMappingsEmptyWithoutServiceCallFacts() throws Exception {
    Tool tool =
        RequirementBriefTool.class
            .getMethod("captureRequirementBrief", RequirementBriefCapture.class)
            .getAnnotation(Tool.class);
    String description = String.join("\n", tool.value());

    assertTrue(description.contains("\"dataMappings\": []"), description);
    assertTrue(description.contains("no positive SERVICE_CALL"), description);
    assertTrue(description.contains("leave dataMappings empty"), description);
    assertTrue(description.contains("stage"), description);
    assertTrue(description.contains("sourceFactId"), description);
  }

  @Test
  void absentDataMappingsCaptureAsEmptyList() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    tool.captureRequirementBrief(
        new RequirementBriefCapture(
            "Greeting endpoint", List.of(), List.of(), List.of(), "summary"));

    RequirementBrief brief = getBrief("conv-brief").orElseThrow();
    assertTrue(brief.dataMappings().isEmpty());
  }

  private static RequirementFact serviceCallFact(
      String sourceFactId, String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        sourceFactId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        "Call " + participant + " " + operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static CatalogBindingHint catalogHint(
      String serviceCallId,
      String sourceFactId,
      String operationQuery,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      Instant observedAt) {
    return new CatalogBindingHint(
        "2",
        serviceCallId,
        sourceFactId,
        operationQuery,
        systemId,
        specificationGroupId,
        specificationId,
        integrationOperationId,
        "http",
        "POST",
        "/tasks",
        "2024.4",
        observedAt,
        "evidence-" + serviceCallId);
  }
}
