package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class RequirementBriefProjectorTest {

  @Test
  void projectsRockyInboundAndOutboundRolesFromApprovedFlow() {
    RequirementBrief projected = RequirementBriefProjector.project(rockyBriefCandidate());

    assertEquals(
        List.of("task-start"),
        projected.entryPoints().stream().map(RequirementEntryPoint::entryPointId).toList());
    assertEquals("async-api-trigger", projected.entryPoints().getFirst().capabilityKey());
    assertEquals(
        List.of("create-task", "task-result"),
        projected.serviceCalls().stream().map(RequirementServiceCall::serviceCallId).toList());
    assertEquals("Salesforce", projected.serviceCalls().getFirst().participant());
    assertEquals("createTask", projected.serviceCalls().getFirst().operation());
    assertEquals("OM", projected.serviceCalls().get(1).participant());
    assertEquals("onTaskResult", projected.serviceCalls().get(1).operation());
    MappingIntent mapping = projected.mappingIntents().getFirst();
    assertEquals("create-task", mapping.sourceRef());
    assertEquals(MappingPort.RESPONSE, mapping.sourcePort());
    assertEquals("task-result", mapping.targetRef());
    assertEquals(MappingPort.REQUEST, mapping.targetPort());
  }

  @Test
  void overwritesCapturedPortsFromFlowDirections() {
    MappingIntent capturedWrongPorts =
        new MappingIntent(
            "response-create-task-to-task-result",
            "create-task",
            MappingPort.OUTPUT,
            "task-result",
            MappingPort.OUTPUT,
            List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")));
    RequirementBrief projected =
        RequirementBriefProjector.project(
            rockyBriefCandidate().withMappingIntents(List.of(capturedWrongPorts)));

    MappingIntent mapping = projected.mappingIntents().getFirst();
    assertEquals("create-task", mapping.sourceRef());
    assertEquals(MappingPort.RESPONSE, mapping.sourcePort());
    assertEquals("task-result", mapping.targetRef());
    assertEquals(MappingPort.REQUEST, mapping.targetPort());
  }

  @Test
  void projectsExplicitMappingsAndOmitsPassThroughRowsFromIntents() {
    MappingIntent explicit =
        new MappingIntent(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.id", "$.petId", null, MappingRuleStatus.PROPOSED)));
    MappingIntent passThrough =
        new MappingIntent(
            "map-resp",
            "call-1",
            MappingPort.RESPONSE,
            "trigger-1",
            MappingPort.OUTPUT,
            List.of());
    RequirementBrief projected =
        RequirementBriefProjector.project(briefWithIntents(List.of(explicit, passThrough)));

    assertEquals(List.of(explicit), projected.mappingIntents());
  }

  @Test
  void groupsFiveRulesAtOneBoundaryAndOmitsIdentityOnlyAuto() {
    MappingIntent fiveRules =
        new MappingIntent(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("$.orderId", "$.orderId", null, MappingRuleStatus.AUTO),
                new MappingIntentRule("$.userId", "$.personId", null, MappingRuleStatus.PROPOSED),
                new MappingIntentRule("$.name", "$.fullName", null, MappingRuleStatus.PROPOSED),
                new MappingIntentRule(
                    "$.createdAt", "$.registrationDate", null, MappingRuleStatus.PROPOSED),
                new MappingIntentRule("$.status", "$.state", null, MappingRuleStatus.PROPOSED)));
    MappingIntent identityOnly =
        new MappingIntent(
            "map-resp",
            "call-1",
            MappingPort.RESPONSE,
            "trigger-1",
            MappingPort.OUTPUT,
            List.of(new MappingIntentRule("$.id", "$.id", null, MappingRuleStatus.AUTO)));
    RequirementBrief projected =
        RequirementBriefProjector.project(briefWithIntents(List.of(fiveRules, identityOnly)));

    assertEquals(1, projected.mappingIntents().size());
    MappingIntent intent = projected.mappingIntents().getFirst();
    assertEquals("map-init", intent.mappingIntentId());
    assertEquals(MappingPort.OUTPUT, intent.sourcePort());
    assertEquals(MappingPort.REQUEST, intent.targetPort());
    assertEquals(5, intent.rules().size());
    assertEquals(MappingRuleStatus.AUTO, intent.rules().getFirst().status());
    assertEquals(MappingRuleStatus.PROPOSED, intent.rules().get(1).status());
  }

  @Test
  void mergesRulesThatShareOneSourceToTargetBoundary() {
    MappingIntent request =
        new MappingIntent(
            "request-onTaskStart-to-createTask",
            "trigger-onTaskStart",
            MappingPort.OUTPUT,
            "call-salesforce-createTask",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("name", "Subject", null)));
    MappingIntent subjectFallback =
        new MappingIntent(
            "map-subject-fallback",
            "trigger-onTaskStart",
            MappingPort.OUTPUT,
            "call-salesforce-createTask",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "", "Subject", "{subRequestType} task for order {orderId}")));

    RequirementBrief projected =
        RequirementBriefProjector.project(briefWithIntents(List.of(request, subjectFallback)));

    assertEquals(1, projected.mappingIntents().size());
    MappingIntent merged = projected.mappingIntents().getFirst();
    assertEquals("request-onTaskStart-to-createTask", merged.mappingIntentId());
    assertEquals(2, merged.rules().size());
    assertEquals("name", merged.rules().getFirst().sourcePath());
    assertEquals("Subject", merged.rules().getFirst().targetPath());
    assertEquals("Subject", merged.rules().get(1).targetPath());
  }

  @Test
  void foldsPlaceholderFieldAliasIntoTheCallToCallBoundary() {
    MappingIntent request =
        new MappingIntent(
            "request-onTaskStart-to-createTask",
            "trigger-onTaskStart",
            MappingPort.OUTPUT,
            "call-salesforce-createTask",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("name", "Subject", null)));
    MappingIntent response =
        new MappingIntent(
            "response-createTask-to-onTaskResult",
            "call-salesforce-createTask",
            MappingPort.RESPONSE,
            "call-om-onTaskResult",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "executionId, orderId, processId, executionNumber, taskId",
                    "executionId, orderId, processId, executionNumber, taskId",
                    "Echo the specified identifiers.")));
    MappingIntent processIdAlias =
        new MappingIntent(
            "process-instance-to-process-id",
            "edge-495d48ab0cc3cf30",
            MappingPort.OUTPUT,
            "edge-495d48ab0cc3cf30",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "processInstanceId",
                    "processId",
                    "Use processInstanceId as processId for the response.")));

    RequirementBrief projected =
        RequirementBriefProjector.project(
            omSalesforceBrief(List.of(request, response, processIdAlias)));

    assertEquals(2, projected.mappingIntents().size(), projected.mappingIntents().toString());
    assertEquals(
        "request-onTaskStart-to-createTask",
        projected.mappingIntents().getFirst().mappingIntentId());
    MappingIntent mergedResponse = projected.mappingIntents().get(1);
    assertEquals("response-createTask-to-onTaskResult", mergedResponse.mappingIntentId());
    assertEquals(2, mergedResponse.rules().size());
    assertEquals("processInstanceId", mergedResponse.rules().get(1).sourcePath());
    assertEquals("processId", mergedResponse.rules().get(1).targetPath());
  }

  @Test
  void doesNotFoldAResponsePlaceholderIntoTheRequestBoundary() {
    MappingIntent request =
        new MappingIntent(
            "request-onTaskStart-to-createTask",
            "trigger-onTaskStart",
            MappingPort.OUTPUT,
            "call-salesforce-createTask",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("name", "Subject", null),
                new MappingIntentRule(
                    "executionId, orderId, processInstanceId, executionNumber, taskId",
                    "response context",
                    "Preserve these fields for the response.")));
    MappingIntent response =
        new MappingIntent(
            "response-createTask-to-onTaskResult",
            "edge-aca101ba838d4bb4",
            MappingPort.OUTPUT,
            "edge-aca101ba838d4bb4",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("", "commandType", "Set to completeTask."),
                new MappingIntentRule(
                    "executionId, orderId, processId, executionNumber, taskId",
                    "executionId, orderId, processId, executionNumber, taskId",
                    "Echo the specified identifiers.")));

    RequirementBrief projected =
        RequirementBriefProjector.project(omSalesforceBrief(List.of(request, response)));

    assertEquals(2, projected.mappingIntents().size(), projected.mappingIntents().toString());
    assertEquals(
        "request-onTaskStart-to-createTask",
        projected.mappingIntents().getFirst().mappingIntentId());
    assertEquals(
        "response-createTask-to-onTaskResult",
        projected.mappingIntents().get(1).mappingIntentId());
  }

  @Test
  void keepsIndependentRequestAndResponseBoundariesSeparate() {
    MappingIntent request =
        new MappingIntent(
            "request-onTaskStart-to-createTask",
            "trigger-onTaskStart",
            MappingPort.OUTPUT,
            "call-salesforce-createTask",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("name", "Subject", null)));
    MappingIntent response =
        new MappingIntent(
            "response-createTask-to-onTaskResult",
            "call-salesforce-createTask",
            MappingPort.RESPONSE,
            "call-om-onTaskResult",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")));

    RequirementBrief projected =
        RequirementBriefProjector.project(omSalesforceBrief(List.of(request, response)));

    assertEquals(2, projected.mappingIntents().size());
    assertEquals(
        "request-onTaskStart-to-createTask",
        projected.mappingIntents().getFirst().mappingIntentId());
    assertEquals(
        "response-createTask-to-onTaskResult",
        projected.mappingIntents().get(1).mappingIntentId());
  }

  @Test
  void preservesExistingMappingIntentsInsteadOfReplacingThemFromDataMappings() {
    MappingIntent unresolved =
        new MappingIntent(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("", "$.personId", null, MappingRuleStatus.UNRESOLVED)));
    RequirementBrief projected =
        RequirementBriefProjector.project(
            new RequirementBrief(
                    "Orders",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "Map the request only",
                    "ref",
                    "draft",
                    List.of(),
                    List.of())
                .withMappingIntents(List.of(unresolved)));

    assertEquals(List.of(unresolved), projected.mappingIntents());
  }

  @Test
  void projectsBindingForEachServiceCall() {
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
    RequirementBrief projected =
        RequirementBriefProjector.project(
            briefWithCalls(List.of(), List.of())
                .withFlow(
                    twoOutboundFlow("call-om-result", "Order Management", "onTaskResult",
                        "call-wfm-create-task", "Salesforce WFM", "createTask"))
                .withCatalogBindings(List.of(omHint, wfmHint)));

    assertEquals(2, projected.serviceCalls().size());
    assertEquals("call-om-result", projected.serviceCalls().get(0).serviceCallId());
    assertEquals("call-wfm-create-task", projected.serviceCalls().get(1).serviceCallId());
    assertEquals(
        "op-om", projected.serviceCalls().get(0).catalogBinding().integrationOperationId());
    assertEquals(
        "op-wfm", projected.serviceCalls().get(1).catalogBinding().integrationOperationId());
  }

  @Test
  void preservesDuplicateOperationBindingsByCallId() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint firstHint =
        catalogHint(
            "call-om-result",
            "call-om-result",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-shared",
            observedAt);
    CatalogBindingHint secondHint =
        catalogHint(
            "call-om-again",
            "call-om-again",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-shared",
            observedAt);
    RequirementBrief projected =
        RequirementBriefProjector.project(
            briefWithCalls(List.of(), List.of())
                .withFlow(
                    twoOutboundFlow(
                        "call-om-result",
                        "Order Management",
                        "onTaskResult",
                        "call-om-again",
                        "Order Management",
                        "onTaskResult"))
                .withCatalogBindings(List.of(firstHint, secondHint)));

    assertEquals(2, projected.serviceCalls().size());
    assertEquals("call-om-result", projected.serviceCalls().get(0).serviceCallId());
    assertEquals("call-om-again", projected.serviceCalls().get(1).serviceCallId());
    assertEquals("op-shared", projected.serviceCalls().get(0).catalogBinding().integrationOperationId());
    assertEquals("op-shared", projected.serviceCalls().get(1).catalogBinding().integrationOperationId());
  }

  private static RequirementBrief rockyBriefCandidate() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint startHint =
        catalogHint(
            "task-start",
            "task-start",
            "onTaskStart",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-start",
            "kafka",
            "publish",
            "env05-bss.task.wfms_createWorkOrder.start",
            observedAt);
    CatalogBindingHint createHint =
        catalogHint(
            "create-task",
            "create-task",
            "createTask",
            "sys-sf",
            "sg-sf",
            "spec-sf",
            "op-create",
            "http",
            "POST",
            "/tasks",
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
            "kafka",
            "subscribe",
            "env05-bss.order.command.queue",
            observedAt);
    MappingIntent response =
        new MappingIntent(
            "response-create-task-to-task-result",
            "create-task",
            MappingPort.RESPONSE,
            "task-result",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")));
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Consume onTaskStart, create a Salesforce task, publish onTaskResult",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withFlow(rockyFlow())
        .withCatalogBindings(List.of(startHint, createHint, resultHint))
        .withMappingIntents(List.of(response));
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
        List.of(
            new Transition("start", firstId),
            new Transition(firstId, secondId)));
  }

  private static RequirementBrief briefWithIntents(List<MappingIntent> mappingIntents) {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map the request only",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(mappingIntents);
  }

  private static RequirementBrief omSalesforceBrief(List<MappingIntent> mappingIntents) {
    RequirementFact trigger =
        new RequirementFact(
            "trigger-onTaskStart",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "async-api-trigger",
            "Consume OM onTaskStart",
            "om-order-lifecycle-manager-WFMS",
            "onTaskStart",
            "env05-bss.task.wfms_createWorkOrder.start",
            "",
            "");
    RequirementFact createTask =
        serviceCallFact(
            "fact-create-task",
            "call-salesforce-createTask",
            "Salesforce WFM",
            "createTask");
    RequirementFact onTaskResult =
        serviceCallFact(
            "fact-on-task-result",
            "call-om-onTaskResult",
            "om-order-lifecycle-manager-WFMS",
            "onTaskResult");
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Consume onTaskStart, create a Salesforce task, publish onTaskResult",
            "ref",
            "draft",
            List.of(trigger, createTask, onTaskResult),
            List.of())
        .withMappingIntents(mappingIntents);
  }

  private static RequirementBrief briefWithCalls(
      List<RequirementFact> facts, List<RequirementServiceCall> serviceCalls) {
    return new RequirementBrief(
        "OM then WFM",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Call OM then Salesforce WFM",
        "ref",
        "draft",
        facts,
        List.of(),
        serviceCalls,
        List.of(),
        List.of());
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
    return catalogHint(
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
        observedAt);
  }

  private static CatalogBindingHint catalogHint(
      String interactionId,
      String sourceFactId,
      String operationQuery,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      String protocol,
      String method,
      String path,
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
        protocol,
        method,
        path,
        "2024.4",
        observedAt,
        "evidence-" + interactionId);
  }
}
