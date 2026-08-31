package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.time.Instant;
import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedOperation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedTrigger;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/** Shared design-input fixtures: one approved brief and the captures that project onto it. */
final class ChainSemanticCaptureFixtures {

  static final String MAPPING_INTENT_ID = "map-body";

  /** Node id the adapter gives the brief's only service call. */
  static final String SERVICE_CALL_NODE_ID = "call-1";

  private ChainSemanticCaptureFixtures() {}

  /** HTTP trigger, one script step, one outbound call. No mapping. */
  static RequirementBrief approvedBrief() {
    return new RequirementBrief(
        "Orders",
        List.of("HTTP POST /orders"),
        List.of("Reject an order without a customer id"),
        List.of("The Orders API is reachable"),
        List.of(),
        "Create order",
        "draft-1",
        "draft",
        List.of(
            new RequirementFact(
                "trigger-1",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.ENDPOINT,
                "http-trigger",
                "HTTP POST /orders",
                "",
                "createOrder",
                "",
                "POST",
                "/orders",
                ""),
            new RequirementFact(
                "fact-call",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.SERVICE_CALL,
                "http-service-call",
                "Create an order via Orders API",
                "Orders API",
                "getOrder",
                "",
                "",
                "",
                "call-1")),
        List.of(),
        List.of(
            new RequirementEntryPoint(
                "http-in", "trigger-1", "http-trigger", "", "POST", "/orders", "createOrder")),
        List.of(
            new RequirementServiceCall(
                "call-1", "fact-call", "Orders API", "getOrder", catalogBinding())),
        List.of(),
        List.of());
  }

  /** Resolved catalog binding for the brief's only service call. */
  static CatalogBindingHint catalogBinding() {
    return new CatalogBindingHint(
        "2",
        "call-1",
        "fact-call",
        "GET /orders/{id}",
        "system-1",
        "group-1",
        "spec-1",
        "operation-1",
        "http",
        "GET",
        "/orders/{id}",
        "v1",
        Instant.EPOCH,
        "catalog-read:system-1/spec-1/operation-1");
  }

  /** The same brief plus one explicit mapping whose refs still point at requirement facts. */
  static RequirementBrief briefWithMapping() {
    return approvedBrief()
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    MAPPING_INTENT_ID,
                    "trigger-1",
                    MappingPort.OUTPUT,
                    "fact-call",
                    MappingPort.REQUEST,
                    List.of(new MappingIntentRule("id", "orderId", null)))));
  }

  /** Catalog Kafka consume: the binding lives on the trigger, not on a service-call node. */
  static RequirementBrief catalogBoundAsyncApiTriggerBrief() {
    return new RequirementBrief(
        "OM consume",
        List.of("Kafka consume onTaskStart"),
        List.of(),
        List.of(),
        List.of(),
        "Consume OM WFMS",
        "draft-1",
        "draft",
        List.of(
            new RequirementFact(
                "fact-consume",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.ENDPOINT,
                "async-api-trigger",
                "Consume onTaskStart",
                "OM WFMS",
                "onTaskStart",
                "",
                "",
                "",
                "consume-om")),
        List.of(),
        List.of(
            new RequirementEntryPoint(
                "async-in",
                "fact-consume",
                "async-api-trigger",
                "",
                "",
                "",
                "onTaskStart")),
        List.of(
            new RequirementServiceCall(
                "consume-om",
                "fact-consume",
                "OM WFMS",
                "onTaskStart",
                new CatalogBindingHint(
                    "2",
                    "consume-om",
                    "fact-consume",
                    "onTaskStart",
                    "sys-om",
                    "sg-om",
                    "spec-om",
                    "op-om",
                    "kafka",
                    "subscribe",
                    "task.start",
                    "catalog",
                    Instant.EPOCH,
                    "ev"))),
        List.of(),
        List.of());
  }

  static ChainSemanticCapture linearCapture() {
    return capture(null);
  }

  static ChainSemanticCapture mappedCapture() {
    return capture(MAPPING_INTENT_ID);
  }

  private static ChainSemanticCapture capture(String mappingIntentId) {
    return new ChainSemanticCapture(
        "chain-orders",
        List.of(
            new CapturedEntryPoint(
                "http-in",
                "trigger-http",
                "op-shared",
                0,
                List.of("trigger-1"),
                "Create order",
                null)),
        List.of(new CapturedTrigger("trigger-http", List.of("trigger-1"))),
        List.of(new CapturedOperation("op-shared", "script", List.of())),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new CapturedEdge("http-in", "op-shared", null, null, null, null, null, null),
            new CapturedEdge(
                "op-shared",
                SERVICE_CALL_NODE_ID,
                null,
                null,
                null,
                null,
                null,
                mappingIntentId)),
        List.of());
  }

  /**
   * Rocky OM/Salesforce WFM: inbound Kafka start, outbound HTTP create, outbound Kafka result.
   * Mapping intents are cleared so topology tests do not also require a placed mapping.
   */
  static RequirementBrief rockyBrief() {
    return RequirementBriefProjector.project(rockyBriefCandidate()).withMappingIntents(List.of());
  }

  /** Same Rocky flow with the create-task response to task-result request mapping kept. */
  static RequirementBrief rockyBriefWithMapping() {
    return RequirementBriefProjector.project(rockyBriefCandidate());
  }

  static ChainSemanticCapture rockyCapture() {
    return rockyCapture(
        List.of(new CapturedOperation("mapper-1", "script", List.of())),
        List.of(
            new CapturedEdge("task-start", "create-task", null, null, null, null, null, null),
            new CapturedEdge("create-task", "mapper-1", null, null, null, null, null, null),
            new CapturedEdge("mapper-1", "task-result", null, null, null, null, null, null)));
  }

  static ChainSemanticCapture rockyCapture(
      List<CapturedOperation> operations, List<CapturedEdge> edges) {
    return new ChainSemanticCapture(
        "om-salesforce-wfm",
        operations,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        edges,
        List.of());
  }

  private static RequirementBrief rockyBriefCandidate() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
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
        .withCatalogBindings(
            List.of(
                rockyHint(
                    "task-start",
                    "onTaskStart",
                    "sys-om",
                    "sg-om",
                    "spec-om",
                    "op-start",
                    "kafka",
                    "publish",
                    "env05-bss.task.wfms_createWorkOrder.start",
                    observedAt),
                rockyHint(
                    "create-task",
                    "createTask",
                    "sys-sf",
                    "sg-sf",
                    "spec-sf",
                    "op-create",
                    "http",
                    "POST",
                    "/tasks",
                    observedAt),
                rockyHint(
                    "task-result",
                    "onTaskResult",
                    "sys-om",
                    "sg-om",
                    "spec-om",
                    "op-result",
                    "kafka",
                    "subscribe",
                    "env05-bss.order.command.queue",
                    observedAt)))
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    "response-create-task-to-task-result",
                    "create-task",
                    MappingPort.RESPONSE,
                    "task-result",
                    MappingPort.REQUEST,
                    List.of(new MappingIntentRule("", "commandType", "Set to completeTask.")))));
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

  private static CatalogBindingHint rockyHint(
      String interactionId,
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
        interactionId,
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
