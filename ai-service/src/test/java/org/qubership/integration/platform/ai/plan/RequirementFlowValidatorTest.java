package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

class RequirementFlowValidatorTest {

  @Test
  void supportedInboundKeysMatchInScopeTriggerPolicy() {
    assertEquals(
        ChainElementFamilies.TRIGGERS.stream()
            .filter(
                type ->
                    ChainElementFamilies.bindingMode(type)
                        != ChainElementFamilies.BindingMode.UNSUPPORTED_IN_CREATE)
            .collect(Collectors.toUnmodifiableSet()),
        RequirementFlowValidator.supportedInboundCapabilityKeys());
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
  void rejectsInboundInteractionWithAPredecessor() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("http-start", INBOUND, "Caller", "GET /start"),
                interaction("publish-event", INBOUND, "Kafka service", "onTaskStart")),
            List.of(edge("http-start", "publish-event")));

    assertEquals(
        Optional.of(
            "requirement flow inbound interaction publish-event has a predecessor and cannot be an"
                + " entry point"),
        RequirementFlowValidator.validateStructure(flow));
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
  void acceptsKafkaPublishBindingOnOutboundServiceCall() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("http-start", INBOUND, "Caller", "GET /start"),
                interaction("publish-event", OUTBOUND, "Kafka service", "onTaskStart")),
            List.of(edge("http-start", "publish-event")));
    RequirementFact httpTrigger =
        new RequirementFact(
            "http-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /start",
            "",
            "",
            "",
            "GET",
            "/start");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(
            flow,
            List.of(httpTrigger),
            List.of(kafkaPublishHint("publish-event", "onTaskStart"))));
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
            "Expose GET /orders",
            "",
            "",
            "",
            "GET",
            "/orders");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(flow, List.of(nativeHttp), List.of()));
  }

  @Test
  void acceptsNativeKafkaSenderFactWithoutCatalogBinding() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("http-start", INBOUND, "Caller", "POST /publish-event"),
                interaction("kafka-send-event", OUTBOUND, "Kafka", "publish")),
            List.of(edge("http-start", "kafka-send-event")));
    RequirementFact nativeHttp =
        new RequirementFact(
            "http-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose POST /publish-event",
            "",
            "",
            "",
            "POST",
            "/publish-event");
    RequirementFact nativeKafkaSender =
        new RequirementFact(
            "kafka-send-event",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-sender-2",
            "Publish the request body to Kafka");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(
            flow, List.of(nativeHttp, nativeKafkaSender), List.of()));
    assertFalse(
        RequirementFlowValidator.requiresCatalogBinding(
            flow.interaction("kafka-send-event").orElseThrow(),
            List.of(nativeHttp, nativeKafkaSender)));
  }

  @Test
  void acceptsNativeChainTriggerFactWithoutCatalogBinding() {
    RequirementFlow flow =
        flow(List.of(interaction("chain-entry", INBOUND, "Parent chain", "start")), List.of());
    RequirementFact nativeChainTrigger =
        new RequirementFact(
            "chain-entry",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "chain-trigger-2",
            "Start from a parent chain");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(flow, List.of(nativeChainTrigger), List.of()));
  }

  @Test
  void acceptsDirectHttpSenderWithoutCatalogBinding() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("http-entry", INBOUND, "Caller", "GET /greeting"),
                interaction("send-greeting", OUTBOUND, "Greeting service", "GET /hello")),
            List.of(edge("http-entry", "send-greeting")));
    RequirementFact nativeHttp =
        new RequirementFact(
            "http-entry",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /greeting",
            "",
            "",
            "",
            "GET",
            "/greeting");
    RequirementFact directSender =
        new RequirementFact(
            "send-greeting",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-sender",
            "Send GET to https://greetings.com/hello",
            "",
            "",
            "",
            "GET",
            "https://greetings.com/hello",
            "");

    assertEquals(
        Optional.empty(),
        RequirementFlowValidator.validateBindings(flow, List.of(nativeHttp, directSender), List.of()));
    assertFalse(
        RequirementFlowValidator.requiresCatalogBinding(
            flow.interaction("send-greeting").orElseThrow(), List.of(nativeHttp, directSender)));
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
    assertTrue(message.get().contains("no trigger type"));
    assertTrue(message.get().contains("sourceFactId=orders-http"));
    assertTrue(message.get().contains("http-trigger"));
    assertFalse(message.get().contains("resolveApiOperation"));
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
        RequirementFlowValidator.validateBindings(
            flow, List.of(asyncTrigger), List.of(omStartHint())));
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
    assertTrue(message.get().contains("no trigger type"));
    assertFalse(message.get().contains("resolveApiOperation"));
  }

  @Test
  void reportsOutboundWithoutSenderAsksForClassification() {
    RequirementFlow flow =
        flow(
            List.of(
                interaction("order-received", INBOUND, "Caller", "POST /orders"),
                interaction("create-order", OUTBOUND, "Order System", "createOrder")),
            List.of(edge("order-received", "create-order")));
    RequirementFact httpTrigger =
        new RequirementFact(
            "order-received",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose POST /orders",
            "",
            "",
            "",
            "POST",
            "/orders");

    Optional<String> message =
        RequirementFlowValidator.validateBindings(flow, List.of(httpTrigger), List.of());
    assertTrue(message.isPresent());
    assertTrue(message.get().contains("create-order"));
    assertTrue(message.get().contains("interactionId=create-order"));
    assertTrue(message.get().contains("OUTBOUND") || message.get().contains("outbound"));
    assertFalse(message.get().contains("resolveApiOperation"));
    assertFalse(message.get().contains("order-received has no catalog binding"));
  }

  @Test
  void customHttpTriggerWithPathDoesNotRequireCatalogBinding() {
    Interaction inbound = interaction("orders-http", INBOUND, "Caller", "GET /orders");
    RequirementFact fact =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /orders",
            "",
            "",
            "",
            "GET",
            "/orders");
    assertFalse(RequirementFlowValidator.requiresCatalogBinding(inbound, List.of(fact)));
    assertEquals(
        RequirementFlowValidator.LookupAction.SKIP,
        RequirementFlowValidator.catalogLookupAction(inbound, List.of(fact)));
  }

  @Test
  void implementedServiceHttpTriggerRequiresCatalogBinding() {
    Interaction inbound = interaction("orders-http", INBOUND, "Orders API", "getOrder");
    RequirementFact fact =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Implement Orders API getOrder",
            "Orders API",
            "getOrder",
            "",
            "",
            "");
    assertTrue(RequirementFlowValidator.requiresCatalogBinding(inbound, List.of(fact)));
  }

  @Test
  void ambiguousHttpTriggerAsksAndDoesNotLookUp() {
    Interaction inbound = interaction("orders-http", INBOUND, "Caller", "HTTP API");
    RequirementFact fact =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose an HTTP API",
            "",
            "",
            "",
            "",
            "");
    assertEquals(
        RequirementFlowValidator.LookupAction.ASK,
        RequirementFlowValidator.catalogLookupAction(inbound, List.of(fact)));
    assertFalse(RequirementFlowValidator.requiresCatalogBinding(inbound, List.of(fact)));
  }

  @Test
  void outboundWithoutSenderCapabilityDoesNotAutoRequireCatalog() {
    Interaction outbound = interaction("create-order", OUTBOUND, "Order System", "createOrder");
    assertEquals(
        RequirementFlowValidator.LookupAction.ASK,
        RequirementFlowValidator.catalogLookupAction(outbound, List.of()));
    assertFalse(RequirementFlowValidator.requiresCatalogBinding(outbound, List.of()));
  }

  @Test
  void mcpTriggerFactAfterHttpTriggerStillRejects() {
    Interaction inbound = interaction("dual-in", INBOUND, "Caller", "GET /orders");
    RequirementFact httpFact =
        new RequirementFact(
            "dual-in",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /orders",
            "",
            "",
            "",
            "GET",
            "/orders");
    RequirementFact mcpFact =
        new RequirementFact(
            "dual-in",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "mcp-trigger",
            "Also expose as an MCP tool");
    assertEquals(
        RequirementFlowValidator.LookupAction.REJECT_UNSUPPORTED,
        RequirementFlowValidator.catalogLookupAction(inbound, List.of(httpFact, mcpFact)));
    Optional<String> error =
        RequirementFlowValidator.validateBindings(
            flow(List.of(inbound), List.of()), List.of(httpFact, mcpFact), List.of());
    assertTrue(error.isPresent());
    assertEquals("MCP trigger is not supported in create-chain yet.", error.orElseThrow());
  }

  @Test
  void mcpTriggerIsRejectedAsUnsupported() {
    Interaction inbound = interaction("mcp-in", INBOUND, "Agent", "tool");
    RequirementFact fact =
        new RequirementFact(
            "mcp-in",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "mcp-trigger",
            "Expose the chain as an MCP tool");
    assertEquals(
        RequirementFlowValidator.LookupAction.REJECT_UNSUPPORTED,
        RequirementFlowValidator.catalogLookupAction(inbound, List.of(fact)));
    Optional<String> error =
        RequirementFlowValidator.validateBindings(flow(List.of(inbound), List.of()), List.of(fact), List.of());
    assertTrue(error.isPresent());
    assertTrue(error.get().contains("not supported"), error.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chain-trigger-2",
        "jms-trigger",
        "kafka-trigger-2",
        "pubsub-trigger",
        "quartz-scheduler",
        "rabbitmq-trigger-2",
        "sds-trigger",
        "sftp-trigger-2"
      })
  void directTriggerCapabilitySkipsCatalogLookup(String capabilityKey) {
    String interactionId = "entry";
    Interaction inbound = interaction(interactionId, INBOUND, "System", "start");
    RequirementFact fact =
        new RequirementFact(
            interactionId,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            capabilityKey,
            "Start the chain");
    assertEquals(
        RequirementFlowValidator.LookupAction.SKIP,
        RequirementFlowValidator.catalogLookupAction(inbound, List.of(fact)));
    assertEquals(
        Optional.of("requirement flow interaction " + interactionId + " has an unexpected catalog binding"),
        RequirementFlowValidator.validateBindings(
            flow(List.of(inbound), List.of()),
            List.of(fact),
            List.of(kafkaPublishHint(interactionId, "start"))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "graphql-sender",
        "http-sender",
        "jms-sender",
        "kafka-sender-2",
        "mail-sender",
        "pubsub-sender",
        "rabbitmq-sender-2",
        "scs-sender"
      })
  void directSenderCapabilitySkipsCatalogLookup(String capabilityKey) {
    String interactionId = "send";
    Interaction outbound = interaction(interactionId, OUTBOUND, "Target", "publish");
    RequirementFact fact =
        new RequirementFact(
            interactionId,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            capabilityKey,
            "Send directly");
    assertEquals(
        RequirementFlowValidator.LookupAction.SKIP,
        RequirementFlowValidator.catalogLookupAction(outbound, List.of(fact)));
    assertEquals(
        Optional.of("requirement flow interaction " + interactionId + " has an unexpected catalog binding"),
        RequirementFlowValidator.validateBindings(
            flow(
                List.of(
                    interaction("http-start", INBOUND, "Caller", "GET /start"),
                    outbound),
                List.of(edge("http-start", interactionId))),
            List.of(
                new RequirementFact(
                    "http-start",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CAPABILITY,
                    "http-trigger",
                    "Expose GET /start",
                    "",
                    "",
                    "",
                    "GET",
                    "/start"),
                fact),
            List.of(kafkaPublishHint(interactionId, "publish"))));
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
