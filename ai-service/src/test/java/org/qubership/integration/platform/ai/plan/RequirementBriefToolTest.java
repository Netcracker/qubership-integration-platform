package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
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
  void storesTypedMappingIntentsFromCapture() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    CapturedMappingIntent intent =
        new CapturedMappingIntent(
            "map-request",
            "trigger-onTaskStart",
            "call-salesforce-createTask",
            List.of(new MappingIntentRule("name", "Subject", null)));

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "OM to Salesforce WFM",
                List.of(),
                List.of(),
                List.of(),
                "Map request fields",
                null,
                null,
                List.of(),
                List.of(),
                List.of(intent)));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief brief = getBrief("conv-brief").orElseThrow();
    assertEquals(1, brief.mappingIntents().size());
    assertEquals("map-request", brief.mappingIntents().getFirst().mappingIntentId());
    assertEquals("name", brief.mappingIntents().getFirst().rules().getFirst().sourcePath());
    assertEquals("Subject", brief.mappingIntents().getFirst().rules().getFirst().targetPath());
  }

  @Test
  void assignsPortsFromApprovedRockyFlowWhenCaptureOmitsThem() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint createHint =
        catalogHint(
            "create-task",
            "create-task",
            "createTask",
            "sys-sf",
            "sg-sf",
            "spec-sf",
            "op-create",
            observedAt);
    CatalogBindingHint resultHint =
        catalogHint(
            "task-result",
            "task-result",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-result",
            observedAt);
    RequirementDraft approved =
        readyDraft(
            "Consume onTaskStart, create a Salesforce task, publish onTaskResult",
            List.of(),
            new RequirementFlow(
                List.of(
                    new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
                    new Interaction(
                        "create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
                    new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
                List.of(
                    new Transition("task-start", "create-task"),
                    new Transition("create-task", "task-result"))),
            List.of(createHint, resultHint));
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

    CapturedMappingIntent captured =
        new CapturedMappingIntent(
            "response-create-task-to-task-result",
            "create-task",
            "task-result",
            List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")));
    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "OM to Salesforce WFM",
                List.of(),
                List.of(),
                List.of(),
                "Map the createTask response into onTaskResult.",
                null,
                approved.planningText(),
                List.of(),
                List.of(),
                List.of(captured)));

    assertTrue(result.contains("Requirement brief captured"), result);
    MappingIntent mapping = getBrief("conv-brief").orElseThrow().mappingIntents().getFirst();
    assertEquals("create-task", mapping.sourceRef());
    assertEquals(MappingPort.RESPONSE, mapping.sourcePort());
    assertEquals("task-result", mapping.targetRef());
    assertEquals(MappingPort.REQUEST, mapping.targetPort());
  }

  @Test
  void emptyMappingIntentsStayPassThrough() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Pass-through order flow",
                List.of(),
                List.of(),
                List.of(),
                "Identity copy only"));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief brief = getBrief("conv-brief").orElseThrow();
    assertTrue(brief.mappingIntents().isEmpty());
  }

  @Test
  void recordsEntryPointsFromApprovedInboundFlow() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
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
    CatalogBindingHint callHint =
        catalogHint(
            "call-1",
            "call-1",
            "getPetById",
            "sys-pet",
            "sg-pet",
            "spec-pet",
            "op-pet",
            observedAt);
    RequirementDraft approved =
        readyDraft(
            "Consume events and look up a pet",
            List.of(kafka),
            new RequirementFlow(
                List.of(
                    new Interaction(
                        "trigger-1", Direction.INBOUND, "Events", "consumeUserEvent", ""),
                    new Interaction("call-1", Direction.OUTBOUND, "Petstore Ext", "getPetById", "")),
                List.of(new Transition("trigger-1", "call-1"))),
            List.of(callHint));
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

    String result =
        tool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Kafka pet lookup",
                List.of(),
                List.of(),
                List.of(),
                "Consume events and look up a pet",
                null,
                approved.planningText(),
                List.of(),
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
    assertTrue(brief.mappingIntents().isEmpty());
  }

  @Test
  void pinsApprovedFlowAndBindingsInsteadOfAgentCopies() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint omHint =
        catalogHint(
            "call-om-result",
            "call-om-result",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-om",
            observedAt);
    CatalogBindingHint wfmHint =
        catalogHint(
            "call-wfm-create-task",
            "call-wfm-create-task",
            "createTask",
            "sys-wfm",
            "sg-wfm",
            "spec-wfm",
            "op-wfm",
            observedAt);
    RequirementFlow flow =
        twoOutboundFlow(
            "call-om-result", "Order Management", "onTaskResult",
            "call-wfm-create-task", "Salesforce WFM", "createTask");
    RequirementDraft approved =
        readyDraft("Call OM then Salesforce WFM", List.of(), flow, List.of(omHint, wfmHint));
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
            List.of(),
            List.of());
    RequirementBrief pinned = RequirementBriefTool.pinApprovedDraft(agentBrief, approved);

    assertEquals(approved.facts(), pinned.facts());
    assertEquals(approved.planningText(), pinned.approvedDraftText());
    assertEquals(approved.flow(), pinned.flow());
    assertEquals(approved.catalogBindings(), pinned.catalogBindings());

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
                List.of(),
                List.of()));

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief stored = getBrief("conv-brief").orElseThrow();
    assertEquals(2, stored.serviceCalls().size());
    assertEquals("call-om-result", stored.serviceCalls().get(0).serviceCallId());
    assertEquals("call-wfm-create-task", stored.serviceCalls().get(1).serviceCallId());
    assertEquals(omHint, stored.serviceCalls().get(0).catalogBinding());
    assertEquals(wfmHint, stored.serviceCalls().get(1).catalogBinding());
  }

  @Test
  void pinsApprovedDraftWhenCaptureJsonOmitsServiceCallId() throws Exception {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint omHint =
        catalogHint(
            "call-om-result",
            "call-om-result",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-om",
            observedAt);
    RequirementDraft approved =
        readyDraft(
            "Call OM onTaskResult",
            List.of(),
            twoOutboundFlow(
                "call-om-result", "Order Management", "onTaskResult",
                "unused-second", "Other", "noop"),
            List.of(
                omHint,
                catalogHint(
                    "unused-second",
                    "unused-second",
                    "noop",
                    "sys-other",
                    "sg-other",
                    "spec-other",
                    "op-other",
                    observedAt)));
    RequirementDraftStore draftStore = new RequirementDraftStore();
    draftStore.put("conv-brief", approved);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    tool =
        new RequirementBriefTool(
            captureSession,
            draftStore,
            mapper,
            feedbackStore,
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    RequirementBriefCapture capture =
        mapper.readValue(
            """
            {
              "goal": "Call OM onTaskResult",
              "summary": "summary only",
              "facts": [
                {
                  "polarity": "POSITIVE",
                  "kind": "BEHAVIOR",
                  "text": "Call Order Management onTaskResult"
                }
              ]
            }
            """,
            RequirementBriefCapture.class);

    String result = tool.captureRequirementBrief(capture);

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief stored = getBrief("conv-brief").orElseThrow();
    assertEquals("call-om-result", stored.serviceCalls().getFirst().serviceCallId());
    assertEquals("op-om", stored.serviceCalls().getFirst().catalogBinding().integrationOperationId());
  }

  @Test
  void toolDescriptionDerivesMappingsFromProjectedBusinessInteractions() throws Exception {
    Tool tool =
        RequirementBriefTool.class
            .getMethod("captureRequirementBrief", RequirementBriefCapture.class)
            .getAnnotation(Tool.class);
    String description = String.join("\n", tool.value());

    assertFalse(description.contains("dataMappings"), description);
    assertFalse(description.contains("stage"), description);
    assertFalse(description.contains("sourcePort"), description);
    assertTrue(description.contains("approved flow transition"), description);
    assertTrue(description.contains("interactionId values"), description);
    assertTrue(description.contains("Omit facts when an approved draft exists"), description);
    assertTrue(description.contains("mappingIntents"), description);
    assertTrue(description.contains("the server assigns them"), description);
    assertFalse(description.contains("source-to-target boundary"), description);
    assertFalse(description.contains("no positive SERVICE_CALL"), description);
    assertFalse(description.contains("If you emit a SERVICE_CALL fact"), description);
  }

  @Test
  void captureRequirementBriefOnWorkerAfterPropagateBindingResolvesConversationId()
      throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    worker.submit(ToolSession::clear).get();
    ToolSession.bind("conv-worker-brief");
    Context toolContext = ToolSession.attachedContext();
    AtomicReference<String> result = new AtomicReference<>();
    try {
      ToolSession.propagateBinding(
              toolContext,
              Multi.createFrom()
                  .item("go")
                  .onItem()
                  .invoke(
                      ignored ->
                          result.set(
                              tool.captureRequirementBrief(
                                  new RequirementBriefCapture(
                                      "Greeting endpoint",
                                      List.of(),
                                      List.of(),
                                      List.of(),
                                      "summary"))))
                  .runSubscriptionOn(worker))
          .collect()
          .asList()
          .await()
          .indefinitely();
    } finally {
      ToolSession.clear();
      worker.shutdownNow();
    }

    assertFalse(
        result.get().contains("no active chat session"), result.get());
    assertTrue(result.get().contains("Requirement brief captured"), result.get());
    assertTrue(getBrief("conv-worker-brief").isPresent());
  }

  @Test
  void absentMappingIntentsCaptureAsEmptyList() {
    org.jboss.logmanager.MDC.put(
        org.qubership.integration.platform.ai.chat.ChatMdc.CONVERSATION_ID, "conv-brief");

    tool.captureRequirementBrief(
        new RequirementBriefCapture(
            "Greeting endpoint", List.of(), List.of(), List.of(), "summary"));

    RequirementBrief brief = getBrief("conv-brief").orElseThrow();
    assertTrue(brief.mappingIntents().isEmpty());
  }

  private static RequirementDraft readyDraft(
      String assembledText,
      List<RequirementFact> facts,
      RequirementFlow flow,
      List<CatalogBindingHint> bindings) {
    return new RequirementDraft(
        true,
        assembledText,
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        false,
        facts,
        false,
        null,
        null,
        flow,
        bindings);
  }

  private static RequirementFlow twoOutboundFlow(
      String firstId,
      String firstParticipant,
      String firstOperation,
      String secondId,
      String secondParticipant,
      String secondOperation) {
    return new RequirementFlow(
        List.of(
            new Interaction("start", Direction.INBOUND, "Caller", "POST /start", ""),
            new Interaction(firstId, Direction.OUTBOUND, firstParticipant, firstOperation, ""),
            new Interaction(
                secondId, Direction.OUTBOUND, secondParticipant, secondOperation, "")),
        List.of(new Transition("start", firstId), new Transition(firstId, secondId)));
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
      String interactionId,
      String sourceFactId,
      String operationQuery,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      Instant observedAt) {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        interactionId,
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
        "evidence-" + interactionId);
  }
}
