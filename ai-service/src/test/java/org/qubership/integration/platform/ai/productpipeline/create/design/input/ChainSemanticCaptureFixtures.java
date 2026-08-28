package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.time.Instant;
import java.util.List;
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
            new CapturedEdge("trigger-http", "op-shared", null, null, null, null, null, null),
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
}
