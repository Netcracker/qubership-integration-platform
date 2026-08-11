package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
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
}
