package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.RequirementFlowValidator.LookupAction;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.CreateChainPipelineMatrixCaptures;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * End-to-end matrix for in-scope create-chain triggers and senders: requirement flow policy,
 * semantic projection, compiler contract, graph compile, and schema validation.
 */
class CreateChainTriggerSenderPipelineMatrixTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private static final String INBOUND_ID = "entry-1";
  private static final String OUTBOUND_ID = "send-1";
  private static final String SCRIPT_FACT_ID = "fact-script";
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DeterministicElementSchemaService schemaService =
      DeterministicElementSchemaService.createForUnitTests(objectMapper);
  private final ChainSemanticCaptureAdapter adapter =
      new ChainSemanticCaptureAdapter(new ChainSemanticCanonicalizer());
  private final ChainSemanticGraphCompiler compiler =
      new DefaultChainSemanticGraphCompiler(
          new DefaultChainSemanticRevisionValidator(), schemaService);

  @ParameterizedTest(name = "{0}")
  @MethodSource("passingCases")
  void walksCreateChainPipelineForEachType(PipelineCase caseSpec) throws JsonProcessingException {
    RequirementBrief brief = caseSpec.brief();
    Interaction lookupInteraction = caseSpec.lookupInteraction(brief.flow());

    assertEquals(
        caseSpec.expectedLookup(),
        RequirementFlowValidator.catalogLookupAction(lookupInteraction, brief.facts()),
        caseSpec.displayName());

    if (caseSpec.expectedLookup() == LookupAction.SKIP) {
      assertTrue(
          RequirementFlowValidator.validateBindings(brief.flow(), brief.facts(), List.of())
              .isEmpty(),
          caseSpec.displayName());
      assertTrue(
          RequirementFlowValidator.validateBindings(
                  brief.flow(), brief.facts(), List.of(fakeBinding(lookupInteraction.interactionId())))
              .isPresent(),
          caseSpec.displayName());
    } else if (caseSpec.expectedLookup() == LookupAction.REQUIRE) {
      assertFalse(brief.catalogBindings().isEmpty(), caseSpec.displayName());
      assertTrue(
          RequirementFlowValidator.validateBindings(
                  brief.flow(), brief.facts(), brief.catalogBindings())
              .isEmpty(),
          caseSpec.displayName());
    }

    ChainSemanticRevision revision =
        adapter.adapt(caseSpec.capture(), "matrix-run", brief, CONTRACT);
    assertSemanticKind(revision, caseSpec);

    assertTrue(
        CONTRACT.elements().containsKey(caseSpec.elementType()),
        caseSpec.elementType() + " must be in the compiler contract");

    List<ResolvedServiceCallBinding> bindings = caseSpec.resolvedBindings(revision);
    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, bindings, brief);
    ChainPlanNode materialized = findNode(graph, caseSpec.elementType());
    assertSchemaAccepts(caseSpec.elementType(), materialized, caseSpec.propertySeeds());
  }

  @Test
  void mcpTriggerCapabilityIsRejected() {
    Interaction inbound = interaction(INBOUND_ID, Direction.INBOUND, "Agent", "tool");
    RequirementFact fact =
        new RequirementFact(
            INBOUND_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "mcp-trigger",
            "Expose the chain as an MCP tool");
    RequirementFlow flow = flow(List.of(inbound), List.of());
    RequirementBrief brief = brief(flow, List.of(fact), List.of(), List.of());

    assertEquals(
        LookupAction.REJECT_UNSUPPORTED,
        RequirementFlowValidator.catalogLookupAction(inbound, brief.facts()));

    Optional<String> error =
        RequirementFlowValidator.validateBindings(flow, brief.facts(), List.of());
    assertTrue(error.isPresent());
    assertTrue(error.orElseThrow().contains("not supported"));
  }

  private static Stream<PipelineCase> passingCases() {
    List<PipelineCase> cases = new ArrayList<>();
    cases.add(
        triggerCase(
            "http-trigger custom",
            "http-trigger",
            LookupAction.SKIP,
            Map.of(),
            customHttpTriggerBrief(),
            customHttpTriggerCapture()));
    cases.add(
        triggerCase(
            "http-trigger implemented service",
            "http-trigger",
            LookupAction.REQUIRE,
            Map.of(),
            implementedServiceHttpTriggerBrief(),
            implementedServiceHttpTriggerCapture()));
    cases.add(
        triggerCase(
            "async-api-trigger",
            "async-api-trigger",
            LookupAction.REQUIRE,
            Map.of(),
            asyncApiTriggerBrief(),
            asyncApiTriggerCapture()));
    for (String triggerType :
        List.of(
            "chain-trigger-2",
            "jms-trigger",
            "kafka-trigger-2",
            "pubsub-trigger",
            "quartz-scheduler",
            "rabbitmq-trigger-2",
            "sds-trigger",
            "sftp-trigger-2")) {
      cases.add(
          directTriggerCase(
              triggerType,
              LookupAction.SKIP,
              propertySeeds().getOrDefault(triggerType, Map.of())));
    }
    for (String senderType :
        List.of(
            "graphql-sender",
            "http-sender",
            "jms-sender",
            "kafka-sender-2",
            "mail-sender",
            "pubsub-sender",
            "rabbitmq-sender-2",
            "scs-sender")) {
      cases.add(
          directSenderCase(
              senderType,
              LookupAction.SKIP,
              propertySeeds().getOrDefault(senderType, Map.of())));
    }
    return cases.stream();
  }

  private static PipelineCase triggerCase(
      String displayName,
      String elementType,
      LookupAction expectedLookup,
      Map<String, String> propertySeeds,
      RequirementBrief brief,
      ChainSemanticCapture capture) {
    return new PipelineCase(
        displayName,
        elementType,
        Role.TRIGGER,
        expectedLookup,
        brief,
        capture,
        propertySeeds);
  }

  private static PipelineCase directTriggerCase(
      String triggerType, LookupAction expectedLookup, Map<String, String> propertySeeds) {
    RequirementFlow flow =
        flow(
            List.of(interaction(INBOUND_ID, Direction.INBOUND, "System", "start")),
            List.of());
    RequirementFact triggerFact = capabilityFact(INBOUND_ID, triggerType, "Start with " + triggerType);
    RequirementFact scriptFact =
        new RequirementFact(
            SCRIPT_FACT_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "Prepare the response");
    RequirementBrief brief =
        brief(
            flow,
            List.of(triggerFact, scriptFact),
            List.of(
                new RequirementEntryPoint(
                    INBOUND_ID, INBOUND_ID, triggerType, "", "", "", "start")),
            List.of());
    ChainSemanticCapture capture = directTriggerCapture(INBOUND_ID);
    return triggerCase(triggerType, triggerType, expectedLookup, propertySeeds, brief, capture);
  }

  private static PipelineCase directSenderCase(
      String senderType, LookupAction expectedLookup, Map<String, String> propertySeeds) {
    RequirementFlow flow =
        flow(
            List.of(
                interaction(INBOUND_ID, Direction.INBOUND, "Caller", "POST /relay"),
                interaction(OUTBOUND_ID, Direction.OUTBOUND, "Target", "send")),
            List.of(new Transition(INBOUND_ID, OUTBOUND_ID)));
    RequirementFact httpTrigger =
        new RequirementFact(
            INBOUND_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose POST /relay",
            "",
            "",
            "",
            "POST",
            "/relay");
    RequirementFact senderFact = capabilityFact(OUTBOUND_ID, senderType, "Send with " + senderType);
    RequirementBrief brief =
        brief(
            flow,
            List.of(httpTrigger, senderFact),
            List.of(
                new RequirementEntryPoint(
                    INBOUND_ID, INBOUND_ID, "http-trigger", "", "POST", "/relay", "POST /relay")),
            List.of());
    ChainSemanticCapture capture = directSenderCapture(OUTBOUND_ID);
    return new PipelineCase(
        senderType, senderType, Role.SENDER, expectedLookup, brief, capture, propertySeeds);
  }

  private static RequirementBrief customHttpTriggerBrief() {
    RequirementFlow flow =
        flow(
            List.of(interaction(INBOUND_ID, Direction.INBOUND, "Caller", "POST /orders")),
            List.of());
    RequirementFact triggerFact =
        new RequirementFact(
            INBOUND_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "POST /orders",
            "",
            "",
            "",
            "POST",
            "/orders");
    RequirementFact scriptFact =
        new RequirementFact(
            SCRIPT_FACT_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "Prepare the response");
    return brief(
        flow,
        List.of(triggerFact, scriptFact),
        List.of(
            new RequirementEntryPoint(
                INBOUND_ID, INBOUND_ID, "http-trigger", "", "POST", "/orders", "POST /orders")),
        List.of());
  }

  private static ChainSemanticCapture customHttpTriggerCapture() {
    return directTriggerCapture(INBOUND_ID);
  }

  private static RequirementBrief implementedServiceHttpTriggerBrief() {
    CatalogBindingHint hint =
        catalogHint(INBOUND_ID, "getGeo", "sys-geo", "sg-geo", "spec-geo", "op-geo", "GET", "/geo/{id}");
    RequirementFlow flow =
        flow(
            List.of(interaction(INBOUND_ID, Direction.INBOUND, "GeoSite", "getGeo")),
            List.of());
    RequirementFact triggerFact =
        new RequirementFact(
            INBOUND_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Implement GeoSite getGeo",
            "GeoSite",
            "getGeo",
            "",
            "GET",
            "");
    RequirementFact scriptFact =
        new RequirementFact(
            SCRIPT_FACT_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "Prepare the response");
    return brief(
        flow,
        List.of(triggerFact, scriptFact),
        List.of(
            new RequirementEntryPoint(
                INBOUND_ID, INBOUND_ID, "http-trigger", "", "GET", "", "getGeo")),
        List.of(hint));
  }

  private static ChainSemanticCapture implementedServiceHttpTriggerCapture() {
    return directTriggerCapture(INBOUND_ID);
  }

  private static RequirementBrief asyncApiTriggerBrief() {
    CatalogBindingHint hint =
        catalogHint(
            INBOUND_ID,
            "onTaskStart",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-om",
            "publish",
            "task.wfms_createWorkOrder.start");
    RequirementFlow flow =
        flow(
            List.of(interaction(INBOUND_ID, Direction.INBOUND, "OM WFMS", "onTaskStart")),
            List.of());
    RequirementFact consumeFact =
        new RequirementFact(
            INBOUND_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "async-api-trigger",
            "Consume onTaskStart",
            "OM WFMS",
            "onTaskStart",
            "",
            "",
            "",
            "consume-om");
    RequirementFact scriptFact =
        new RequirementFact(
            SCRIPT_FACT_ID,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "Prepare the response");
    return brief(
        flow,
        List.of(consumeFact, scriptFact),
        List.of(
            new RequirementEntryPoint(
                INBOUND_ID, INBOUND_ID, "async-api-trigger", "", "", "", "onTaskStart")),
        List.of(
            new RequirementServiceCall(
                "consume-om", INBOUND_ID, "OM WFMS", "onTaskStart", hint)),
        List.of(hint));
  }

  private static ChainSemanticCapture asyncApiTriggerCapture() {
    return CreateChainPipelineMatrixCaptures.directTriggerWithScript(INBOUND_ID, SCRIPT_FACT_ID);
  }

  private static ChainSemanticCapture directTriggerCapture(String entryPointId) {
    return CreateChainPipelineMatrixCaptures.directTriggerWithScript(entryPointId, SCRIPT_FACT_ID);
  }

  private static ChainSemanticCapture directSenderCapture(String outboundId) {
    return CreateChainPipelineMatrixCaptures.directSenderRelay(INBOUND_ID, outboundId);
  }

  private static Map<String, Map<String, String>> propertySeeds() {
    Map<String, Map<String, String>> seeds = new LinkedHashMap<>();
    seeds.put("chain-trigger-2", Map.of("elementId", INBOUND_ID));
    seeds.put("jms-trigger", jmsProperties());
    seeds.put("jms-sender", jmsSenderProperties());
    seeds.put("kafka-trigger-2", Map.of("groupId", "grp", "topicsClassifierName", "orders-in"));
    seeds.put("pubsub-trigger", pubsubProperties());
    seeds.put("pubsub-sender", pubsubProperties());
    seeds.put("quartz-scheduler", Map.of("cron", "0 */5 * ? * *", "deleteJob", "false"));
    seeds.put("rabbitmq-trigger-2", Map.of("queues", "orders", "exchange", "orders"));
    seeds.put("sds-trigger", Map.of("jobId", "job-1", "prohibitParallelRun", "false"));
    seeds.put(
        "sftp-trigger-2",
        Map.of(
            "connectUrl",
            "localhost:22/inbox",
            "scheduler.cron",
            "0 */5 * ? * *",
            "password",
            "password"));
    seeds.put("graphql-sender", Map.of("query", "query { id }", "uri", "http://localhost/graphql"));
    seeds.put("http-sender", Map.of("httpMethod", "GET", "uri", "http://localhost/orders"));
    seeds.put("mail-sender", Map.of("from", "sender@example.com", "url", "smtp://localhost", "password", "password"));
    seeds.put(
        "rabbitmq-sender-2",
        Map.of("exchange", "orders", "routingKey", "orders"));
    seeds.put("scs-sender", Map.of("useCorrelationId", "true", "operation", "DELETE"));
    return seeds;
  }

  private static Map<String, String> jmsProperties() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("initialContextFactory", "org.apache.activemq.jndi.ActiveMQInitialContextFactory");
    properties.put("providerUrl", "tcp://localhost:61616");
    properties.put("connectionFactoryName", "ConnectionFactory");
    properties.put("destinationName", "orders");
    properties.put("destinationType", "queue");
    properties.put("acknowledgmentMode", "AUTO_ACKNOWLEDGE");
    properties.put("password", "password");
    return properties;
  }

  private static Map<String, String> jmsSenderProperties() {
    Map<String, String> properties = new LinkedHashMap<>(jmsProperties());
    properties.put("jmsMessageType", "Text");
    return properties;
  }

  private static Map<String, String> pubsubProperties() {
    return Map.of(
        "projectId",
        "project-1",
        "destinationName",
        "orders",
        "serviceAccountKey",
        "{}");
  }

  private void assertSemanticKind(ChainSemanticRevision revision, PipelineCase caseSpec) {
    if (caseSpec.role() == Role.TRIGGER) {
      SemanticNode.Trigger trigger =
          revision.nodes().stream()
              .filter(SemanticNode.Trigger.class::isInstance)
              .map(SemanticNode.Trigger.class::cast)
              .filter(node -> caseSpec.elementType().equals(node.capabilityKey()))
              .findFirst()
              .orElseThrow();
      assertEquals(caseSpec.elementType(), trigger.capabilityKey());
      assertTrue(
          revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance),
          caseSpec.displayName());
      return;
    }
    SemanticNode.Operation sender =
        revision.nodes().stream()
            .filter(SemanticNode.Operation.class::isInstance)
            .map(SemanticNode.Operation.class::cast)
            .filter(operation -> OUTBOUND_ID.equals(operation.nodeId()))
            .findFirst()
            .orElseThrow();
    assertEquals(caseSpec.elementType(), sender.elementType());
    assertTrue(
        revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance),
        caseSpec.displayName());
  }

  private void assertSchemaAccepts(
      String elementType, ChainPlanNode node, Map<String, String> seeds)
      throws JsonProcessingException {
    List<PlanProperty> properties = new ArrayList<>();
    if (node.properties() != null) {
      properties.addAll(node.properties());
    }
    for (Map.Entry<String, String> seed : seeds.entrySet()) {
      if (properties.stream().noneMatch(property -> seed.getKey().equals(property.key()))) {
        properties.add(new PlanProperty(seed.getKey(), seed.getValue()));
      }
    }
    for (String required :
        CONTRACT.elements().get(elementType).requiredProperties()) {
      if (properties.stream().noneMatch(property -> required.equals(property.key()))) {
        String seeded = seeds.get(required);
        assertNotNull(
            seeded,
            "Missing seed for contract-required property "
                + required
                + " on "
                + elementType);
        properties.add(new PlanProperty(required, seeded));
      }
    }
    List<PlanProperty> withDefaults =
        schemaService.withUnconditionalSchemaDefaults(elementType, properties);
    ObjectNode patch = objectMapper.createObjectNode();
    ObjectNode props = objectMapper.createObjectNode();
    for (PlanProperty property : withDefaults) {
      putPlanProperty(props, property);
    }
    patch.set("properties", props);
    String validationJson =
        schemaService.validateElementPatch(elementType, objectMapper.writeValueAsString(patch));
    JsonNode result = objectMapper.readTree(validationJson);
    assertTrue(
        result.path("valid").asBoolean(),
        elementType + " schema validation failed: " + validationJson);
  }

  private void putPlanProperty(ObjectNode props, PlanProperty property)
      throws JsonProcessingException {
    String value = property.value();
    if ("integrationOperationAsyncProperties".equals(property.key())
        && value != null
        && !value.isBlank()) {
      props.set(property.key(), objectMapper.readTree(value.trim()));
      return;
    }
    props.put(property.key(), value);
  }

  private static ChainPlanNode findNode(ChainPlanGraph graph, String elementType) {
    return graph.nodes().stream()
        .filter(node -> elementType.equals(node.type()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No graph node of type " + elementType));
  }

  private static CatalogBindingHint fakeBinding(String interactionId) {
    return catalogHint(
        interactionId,
        "operation",
        "sys-1",
        "sg-1",
        "spec-1",
        "op-1",
        "GET",
        "/path");
  }

  private static CatalogBindingHint catalogHint(
      String interactionId,
      String operationQuery,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      String method,
      String path) {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        interactionId,
        interactionId,
        operationQuery,
        systemId,
        specificationGroupId,
        specificationId,
        integrationOperationId,
        catalogProtocol(method),
        method,
        path,
        "v1",
        Instant.EPOCH,
        "catalog-read:" + systemId + "/" + specificationId + "/" + integrationOperationId);
  }

  private static String catalogProtocol(String method) {
    if (method == null) {
      return "http";
    }
    String normalized = method.toLowerCase(java.util.Locale.ROOT);
    if ("publish".equals(normalized)
        || "subscribe".equals(normalized)
        || "send".equals(normalized)
        || "receive".equals(normalized)) {
      return "kafka";
    }
    return "http";
  }

  private static RequirementFact capabilityFact(
      String interactionId, String capabilityKey, String text) {
    return new RequirementFact(
        interactionId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY,
        capabilityKey,
        text);
  }

  private static RequirementBrief brief(
      RequirementFlow flow,
      List<RequirementFact> facts,
      List<RequirementEntryPoint> entryPoints,
      List<CatalogBindingHint> catalogBindings) {
    return brief(flow, facts, entryPoints, List.of(), catalogBindings);
  }

  private static RequirementBrief brief(
      RequirementFlow flow,
      List<RequirementFact> facts,
      List<RequirementEntryPoint> entryPoints,
      List<RequirementServiceCall> serviceCalls,
      List<CatalogBindingHint> catalogBindings) {
    return new RequirementBrief(
        "Matrix chain",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Matrix chain",
        "draft-1",
        "draft",
        facts,
        entryPoints,
        serviceCalls,
        List.of(),
        List.of(),
        flow,
        catalogBindings);
  }

  private static RequirementFlow flow(
      List<Interaction> interactions, List<Transition> transitions) {
    return new RequirementFlow(interactions, transitions);
  }

  private static Interaction interaction(
      String interactionId, Direction direction, String participant, String operation) {
    return new Interaction(interactionId, direction, participant, operation, "");
  }

  private enum Role {
    TRIGGER,
    SENDER
  }

  private record PipelineCase(
      String displayName,
      String elementType,
      Role role,
      LookupAction expectedLookup,
      RequirementBrief brief,
      ChainSemanticCapture capture,
      Map<String, String> propertySeeds) {

    Interaction lookupInteraction(RequirementFlow flow) {
      if (role == Role.SENDER) {
        return flow.interaction(OUTBOUND_ID).orElseThrow();
      }
      return flow.interaction(INBOUND_ID).orElseThrow();
    }

    List<ResolvedServiceCallBinding> resolvedBindings(ChainSemanticRevision revision) {
      if (!"async-api-trigger".equals(elementType)) {
        return List.of();
      }
      CatalogBindingHint hint = brief.catalogBindings().getFirst();
      return List.of(
          new ResolvedServiceCallBinding(
              INBOUND_ID,
              "consume-om",
              "INTERNAL",
              hint.systemId(),
              hint.specificationGroupId(),
              hint.specificationId(),
              hint.integrationOperationId(),
              "kafka",
              "publish",
              hint.path(),
              hint.operationQuery(),
              ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
              hint.release(),
              hint.evidenceRef(),
              "",
              "wfms",
              "g-1"));
    }
  }
}
