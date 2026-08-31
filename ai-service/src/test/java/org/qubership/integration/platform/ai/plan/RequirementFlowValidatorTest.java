package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class RequirementFlowValidatorTest {

  @Test
  void acceptsLinearBusinessFlow() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("task-start", INBOUND, "OM", "onTaskStart"),
                interaction("create-task", OUTBOUND, "Salesforce", "createTask"),
                interaction("task-result", OUTBOUND, "OM", "onTaskResult")),
            List.of(edge("task-start", "create-task"), edge("create-task", "task-result")));

    assertTrue(RequirementFlowValidator.validateStructure(flow).isEmpty());
  }

  @Test
  void acceptsMultipleInboundRoots() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("http-start", INBOUND, "Caller", "POST /orders"),
                interaction("kafka-start", INBOUND, "OM", "onTaskStart"),
                interaction("create-order", OUTBOUND, "Order System", "createOrder")),
            List.of(
                edge("http-start", "create-order"), edge("kafka-start", "create-order")));

    assertTrue(RequirementFlowValidator.validateStructure(flow).isEmpty());
  }

  @Test
  void acceptsBranching() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("order-received", INBOUND, "Caller", "POST /orders"),
                interaction("create-order", OUTBOUND, "Order System", "createOrder"),
                interaction("notify-crm", OUTBOUND, "CRM", "notify")),
            List.of(
                edge("order-received", "create-order"),
                edge("order-received", "notify-crm")));

    assertTrue(RequirementFlowValidator.validateStructure(flow).isEmpty());
  }

  @Test
  void rejectsDuplicateInteractionIds() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("task-start", INBOUND, "OM", "onTaskStart"),
                interaction("task-start", OUTBOUND, "Salesforce", "createTask")),
            List.of());

    assertEquals(
        Optional.of("requirement flow contains duplicate interactionId: task-start"),
        RequirementFlowValidator.validateStructure(flow));
  }

  @Test
  void rejectsDanglingTransitions() {
    RequirementFlow flow =
        flow(
            List.of(interaction("task-start", INBOUND, "OM", "onTaskStart")),
            List.of(edge("task-start", "create-task")));

    assertEquals(
        Optional.of(
            "requirement flow transition references unknown targetInteractionId: create-task"),
        RequirementFlowValidator.validateStructure(flow));
  }

  @Test
  void rejectsCycleBeforeCatalogDiscovery() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("start", INBOUND, "OM", "start"),
                interaction("result", OUTBOUND, "OM", "result")),
            List.of(edge("start", "result"), edge("result", "start")));

    assertEquals(
        Optional.of("requirement flow contains a cycle: result -> start"),
        RequirementFlowValidator.validateStructure(flow));
  }

  @Test
  void rejectsNoInboundRoot() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("create-task", OUTBOUND, "Salesforce", "createTask"),
                interaction("task-result", OUTBOUND, "OM", "onTaskResult")),
            List.of(edge("create-task", "task-result")));

    assertEquals(
        Optional.of("requirement flow has no inbound interaction"),
        RequirementFlowValidator.validateStructure(flow));
  }

  @Test
  void rejectsUnreachableOutboundInteraction() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("task-start", INBOUND, "OM", "onTaskStart"),
                interaction("create-task", OUTBOUND, "Salesforce", "createTask"),
                interaction("task-result", OUTBOUND, "OM", "onTaskResult")),
            List.of(edge("task-start", "create-task")));

    assertEquals(
        Optional.of(
            "requirement flow outbound interaction task-result is unreachable from any inbound"
                + " interaction"),
        RequirementFlowValidator.validateStructure(flow));
  }

  @Test
  void acceptsCatalogBindingOnInboundWithoutNativeTriggerFact() {
    RequirementFlow flow =
        flow(List.of(interaction("task-start", INBOUND, "OM", "onTaskStart")), List.of());

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(flow, List.of(), List.of(omStartHint())));
  }

  @Test
  void rejectsCatalogBindingOnNativeInboundInteraction() {
    RequirementFlow flow =
        flow(List.of(interaction("kafka-start", INBOUND, "Local Kafka", "onTaskStart")), List.of());
    RequirementFact nativeTrigger =
        new RequirementFact(
            "kafka-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-trigger-2",
            "Consume the local task.start topic");

    assertEquals(
        Optional.of("requirement flow interaction kafka-start has an unexpected catalog binding"),
        RequirementFlowValidator.validateBindings(
            flow, List.of(nativeTrigger), List.of(localKafkaHint())));
  }

  @Test
  void acceptsNativeHttpTriggerFactWithoutCatalogBinding() {
    RequirementFlow flow =
        flow(List.of(interaction("orders-http", INBOUND, "Caller", "GET /orders")), List.of());
    RequirementFact nativeHttp =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /orders");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(flow, List.of(nativeHttp), List.of()));
  }

  @Test
  void acceptsInboundCallerWithoutNativeFactOrHint() {
    RequirementFlow flow =
        flow(List.of(interaction("orders-http", INBOUND, "Caller", "GET /orders")), List.of());

    assertEquals(
        Optional.empty(), RequirementFlowValidator.validateBindings(flow, List.of(), List.of()));
  }

  @Test
  void reportsMissingOutboundBindingWithResolveAction() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("order-received", INBOUND, "Caller", "POST /orders"),
                interaction("create-order", OUTBOUND, "Order System", "createOrder")),
            List.of(edge("order-received", "create-order")));

    Optional<String> message =
        RequirementFlowValidator.validateBindings(flow, List.of(), List.of());
    assertTrue(message.isPresent());
    assertTrue(message.get().contains("create-order"));
    assertTrue(message.get().contains("resolveApiOperation"));
    assertTrue(message.get().contains("interactionId=create-order"));
    assertTrue(message.get().contains("OUTBOUND") || message.get().contains("outbound"));
    assertFalse(message.get().contains("native trigger"));
    assertFalse(message.get().contains("http-trigger"));
    assertFalse(message.get().contains("kafka-trigger-2"));
    assertFalse(message.get().contains("order-received has no catalog binding"));
  }

  private static CatalogBindingHint omStartHint() {
    return kafkaPublishHint("task-start", "onTaskStart");
  }

  private static CatalogBindingHint localKafkaHint() {
    return kafkaPublishHint("kafka-start", "onTaskStart");
  }

  private static CatalogBindingHint kafkaPublishHint(String interactionId, String operationQuery) {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        interactionId,
        interactionId,
        operationQuery,
        "sys-om",
        "sg-om",
        "spec-om",
        "op-start",
        "kafka",
        "publish",
        "task.start",
        "catalog",
        Instant.EPOCH,
        "test");
  }

  private static RequirementFlow flow(
      List<Interaction> interactions, List<Transition> transitions) {
    return new RequirementFlow(interactions, transitions);
  }

  private static Interaction interaction(
      String interactionId, Direction direction, String participant, String operation) {
    return new Interaction(interactionId, direction, participant, operation, "");
  }

  private static Transition edge(String sourceInteractionId, String targetInteractionId) {
    return new Transition(sourceInteractionId, targetInteractionId);
  }
}
