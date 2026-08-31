package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class BusinessFirstRequirementFlowPipelineTest {

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void draftBindingsProjectToBriefWithInteractionOwnedMapping() {
    String conversationId = "business-first-pipeline";
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    RequirementDraftStore draftStore = new RequirementDraftStore();
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool draftTool = RequirementDraftTool.withResolutions(draftStore, resolutions);
    draftStore.beginTurn(conversationId);

    RequirementFlow flow = rockyFlow();
    rememberResolution(resolutions, conversationId, flow.interactions().get(0), omStartMatch());
    rememberResolution(resolutions, conversationId, flow.interactions().get(1), salesforceMatch());
    rememberResolution(resolutions, conversationId, flow.interactions().get(2), omResultMatch());

    String draftResult =
        draftTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "OM starts a task, Salesforce creates it, and OM receives the result.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                rockyFacts(),
                null,
                flow));

    assertTrue(draftResult.contains("decision=READY_FOR_PLAN"), draftResult);
    RequirementDraft draft = draftStore.get(conversationId).orElseThrow();
    assertEquals(3, draft.catalogBindings().size());
    assertTrue(
        draft.catalogBindings().stream()
            .allMatch(binding -> "3".equals(binding.schemaVersion())));

    CaptureSession captureSession = new CaptureSession();
    RequirementBriefTool briefTool =
        new RequirementBriefTool(
            captureSession,
            draftStore,
            new ObjectMapper().findAndRegisterModules(),
            new CaptureAttemptFeedbackStore(),
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)));
    MappingIntent mapping =
        new MappingIntent(
            "response-create-task-to-task-result",
            "create-task",
            MappingPort.RESPONSE,
            "task-result",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")));

    String briefResult =
        briefTool.captureRequirementBrief(
            new RequirementBriefCapture(
                "Create and complete a Salesforce task",
                List.of(),
                List.of(),
                List.of(),
                "Map the createTask response into onTaskResult.",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(mapping)));

    assertTrue(briefResult.contains("Requirement brief captured"), briefResult);
    RequirementBrief brief =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId),
                RequirementBrief.class)
            .orElseThrow();
    assertEquals(List.of("task-start"), brief.entryPoints().stream().map(e -> e.entryPointId()).toList());
    assertEquals(
        Set.of("create-task", "task-result"),
        brief.serviceCalls().stream()
            .map(call -> call.serviceCallId())
            .collect(Collectors.toSet()));
    assertEquals("create-task", brief.mappingIntents().getFirst().sourceRef());
    assertEquals("task-result", brief.mappingIntents().getFirst().targetRef());
  }

  private static RequirementFlow rockyFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
            new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
            new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
        List.of(
            new Transition("task-start", "create-task"),
            new Transition("create-task", "task-result")));
  }

  private static List<RequirementFact> rockyFacts() {
    return List.of(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "mapping",
            "Map commandType to completeTask"));
  }

  private static void rememberResolution(
      ConversationApiResolutions resolutions,
      String conversationId,
      Interaction interaction,
      CatalogMatch match) {
    resolutions.remember(
        conversationId,
        InteractionAssessment.resolved(
            interaction.interactionId(),
            new InteractionAssessment.Intent(
                interaction.operation(),
                interaction.participant(),
                interaction.operation(),
                match.method(),
                match.path()),
            match));
  }

  private static CatalogMatch omStartMatch() {
    return match("sys-om", "op-start", "OM", "kafka", "publish", "task.start", "onTaskStart");
  }

  private static CatalogMatch salesforceMatch() {
    return match(
        "sys-salesforce",
        "op-create",
        "Salesforce",
        "http",
        "POST",
        "/tasks",
        "createTask");
  }

  private static CatalogMatch omResultMatch() {
    return match("sys-om", "op-result", "OM", "kafka", "subscribe", "task.result", "onTaskResult");
  }

  private static CatalogMatch match(
      String systemId,
      String operationId,
      String systemName,
      String protocol,
      String method,
      String path,
      String operationName) {
    return new CatalogMatch(
        systemId,
        "sg-" + systemId,
        "spec-" + systemId,
        operationId,
        systemName,
        protocol,
        method,
        path,
        operationName,
        "test:" + operationId);
  }
}
