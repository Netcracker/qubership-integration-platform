package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

class RequirementFlowValidatorTest {

  @Test
  void supportedInboundKeysMatchTheCompilerContract() {
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    Set<String> contractTriggers =
        contract.elements().keySet().stream()
            .filter(ChainElementFamilies::isTrigger)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    assertEquals(contractTriggers, RequirementFlowValidator.supportedInboundCapabilityKeys());
  }

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
  void rejectsInlineResponseOnATerminalServiceCall() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("start", INBOUND, "OM", "onTaskStart"),
                new Interaction(
                    "create-task",
                    OUTBOUND,
                    "Salesforce",
                    "createTask",
                    "",
                    ServiceCallFailureMode.INLINE_RESPONSE),
                new Interaction(
                    "task-result",
                    OUTBOUND,
                    "OM",
                    "onTaskResult",
                    "",
                    ServiceCallFailureMode.INLINE_RESPONSE)),
            List.of(edge("start", "create-task"), edge("create-task", "task-result")));

    assertEquals(
        Optional.of(
            "requirement flow interaction task-result uses INLINE_RESPONSE but has no successor"
                + " response interaction"),
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
  void rejectsNativeTriggerFactMissingFromRequirementFlow() {
    RequirementFlow flow =
        flow(List.of(interaction("task-start", INBOUND, "OM", "onTaskStart")), List.of());
    RequirementFact nativeHttp =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /orders");

    Optional<String> error =
        RequirementFlowValidator.validateBindings(flow, List.of(nativeHttp), List.of(omStartHint()));

    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("orders-http"), error.orElseThrow());
    assertTrue(error.orElseThrow().contains("requirement flow"), error.orElseThrow());
  }

  @Test
  void reportsEntryPointWithoutBindingOrCapabilityFact() {
    RequirementFlow flow =
        flow(List.of(interaction("orders-http", INBOUND, "Caller", "GET /orders")), List.of());

    Optional<String> message =
        RequirementFlowValidator.validateBindings(flow, List.of(), List.of());
    assertTrue(message.isPresent());
    assertTrue(message.get().contains("entry point orders-http"));
    assertTrue(message.get().contains("interactionId=orders-http"));
    assertTrue(message.get().contains("sourceFactId=orders-http"));
    assertTrue(message.get().contains("http-trigger"));
  }

  @Test
  void acceptsEntryPointWithNonNativeCapabilityFact() {
    RequirementFlow flow =
        flow(List.of(interaction("task-start", INBOUND, "OM", "onTaskStart")), List.of());
    RequirementFact asyncTrigger =
        new RequirementFact(
            "task-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "async-api-trigger",
            "Consume the OM task.start topic");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(flow, List.of(asyncTrigger), List.of()));
  }

  @Test
  void rejectsUnknownEntryPointCapabilityBeforeBriefProjection() {
    RequirementFlow flow =
        flow(List.of(interaction("scheduled-run", INBOUND, "Scheduler", "hourly")), List.of());
    RequirementFact inventedTrigger =
        new RequirementFact(
            "scheduled-run",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "quartz-trigger",
            "Run hourly");

    Optional<String> message =
        RequirementFlowValidator.validateBindings(flow, List.of(inventedTrigger), List.of());

    assertTrue(message.isPresent());
    assertTrue(message.orElseThrow().contains("quartz-trigger"), message.orElseThrow());
    assertTrue(message.orElseThrow().contains("quartz-scheduler"), message.orElseThrow());
  }

  @Test
  void acceptsCanonicalQuartzSchedulerEntryPoint() {
    RequirementFlow flow =
        flow(List.of(interaction("scheduled-run", INBOUND, "Scheduler", "hourly")), List.of());
    RequirementFact scheduler =
        new RequirementFact(
            "scheduled-run",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "quartz-scheduler",
            "Run hourly");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(flow, List.of(scheduler), List.of()));
  }

  @Test
  void reportsEntryPointWhoseFirstFactCarriesNoCapabilityKey() {
    RequirementFlow flow =
        flow(List.of(interaction("task-start", INBOUND, "OM", "onTaskStart")), List.of());
    RequirementFact goal =
        new RequirementFact(
            "task-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.GOAL,
            "",
            "Start the chain on onTaskStart");

    Optional<String> message =
        RequirementFlowValidator.validateBindings(flow, List.of(goal), List.of());
    assertTrue(message.isPresent());
    assertTrue(message.get().contains("entry point task-start"));
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
