package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class BriefFlowExtractorTest {

  private final BriefFlowExtractor extractor = new BriefFlowExtractor();

  @Test
  void extractsOrdersIdentityOnlyWhenBriefCarriesFacts() {
    BriefFlowExtractor.ExtractionResult result = extractor.extract(ordersBrief());

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, result).flow();
    assertEquals("Orders", flow.chainName());
    assertEquals("/orders", flow.trigger().endpointOrTopic());
    assertEquals("createOrder", flow.trigger().operationName());
    assertEquals("Orders API", flow.trigger().interfaceName());
    assertEquals("p-orders-api", flow.participants().get(1).participantId());
    assertEquals("Orders API", flow.participants().get(1).displayName());
    assertEquals("create order", flow.steps().getFirst().operationQuery());
  }

  @Test
  void nonOrdersBriefDoesNotInventOrdersPathOrCreateOrder() {
    RequirementBrief petsBrief =
        brief(
            "Pets",
            List.of("HTTP GET /pets"),
            "List pets",
            List.of(
                httpEndpoint(
                    "trigger-1", "HTTP GET /pets findPets", "GET", "/pets", "findPets"),
                serviceCall("call-1", "List pets from Petstore Ext", "Petstore Ext", "GET /pets")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult result = extractor.extract(petsBrief);

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, result).flow();
    assertEquals("Pets", flow.chainName());
    assertEquals("/pets", flow.trigger().endpointOrTopic());
    assertEquals("findPets", flow.trigger().operationName());
    assertEquals("Petstore Ext", flow.participants().get(1).displayName());
    assertEquals("GET /pets", flow.steps().getFirst().operationQuery());
    assertFalse("/orders".equals(flow.trigger().endpointOrTopic()));
    assertFalse("createOrder".equals(flow.trigger().operationName()));
    assertFalse(
        flow.participants().stream().anyMatch(p -> "Orders API".equals(p.displayName())));
    assertFalse(
        flow.steps().stream().anyMatch(s -> "create order".equals(s.operationQuery())));
    assertEquals(
        NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_FIRST,
        flow.bindingResolutionPolicy());
  }

  @Test
  void explicitApiHubProhibitionBecomesCatalogOnlyPolicy() {
    RequirementBrief brief =
        brief(
            "Pet Inventory Check",
            List.of("HTTP POST /demo/pet-inventory/check"),
            "Call the existing Petstore inventory operation",
            List.of(
                httpEndpoint(
                    "trigger-1",
                    "HTTP POST /demo/pet-inventory/check",
                    "POST",
                    "/demo/pet-inventory/check",
                    ""),
                serviceCall(
                    "call-1",
                    "Call the existing Petstore inventory operation",
                    "Petstore Ext",
                    "GET /store/inventory"),
                new RequirementFact(
                    "constraint-apihub",
                    RequirementFactPolarity.NEGATIVE,
                    RequirementFactKind.CONSTRAINT,
                    "apihub",
                    "Do not query APIHub or import a new API specification")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult result = extractor.extract(brief);

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, result).flow();
    assertEquals(
        NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_ONLY,
        flow.bindingResolutionPolicy());
  }

  @Test
  void proseServiceCallWithoutColonReturnsNeedsInput() {
    RequirementBrief brief =
        brief(
            "Pet Inventory Check",
            List.of("POST /demo/pet-inventory/check"),
            "Check the Petstore inventory",
            List.of(
                httpEndpoint(
                    "trigger-1",
                    "POST /demo/pet-inventory/check",
                    "POST",
                    "/demo/pet-inventory/check",
                    ""),
                fact(
                    "call-1",
                    RequirementFactKind.SERVICE_CALL,
                    "Call the existing getInventory operation from imported Petstore Ext using"
                        + " GET /store/inventory")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult.NeedsInput needsInput =
        assertInstanceOf(
            BriefFlowExtractor.ExtractionResult.NeedsInput.class, extractor.extract(brief));
    assertTrue(
        needsInput.missingFacts().stream().anyMatch(m -> m.contains("SERVICE_CALL.participant")),
        () -> needsInput.missingFacts().toString());
  }

  @Test
  void catalogBoundProseWithoutColonReturnsNeedsInput() {
    RequirementBrief brief =
        brief(
            "Pet Inventory Check",
            List.of("POST /demo/pet-inventory/check"),
            "Check the Petstore inventory",
            List.of(
                httpEndpoint(
                    "trigger-1",
                    "POST /demo/pet-inventory/check",
                    "POST",
                    "/demo/pet-inventory/check",
                    ""),
                fact(
                    "call-1",
                    RequirementFactKind.SERVICE_CALL,
                    "Call the catalog-bound Petstore Ext getInventory operation, GET /store/inventory.")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult.NeedsInput needsInput =
        assertInstanceOf(
            BriefFlowExtractor.ExtractionResult.NeedsInput.class, extractor.extract(brief));
    assertTrue(
        needsInput.missingFacts().stream().anyMatch(m -> m.contains("SERVICE_CALL.participant")),
        () -> needsInput.missingFacts().toString());
  }

  @Test
  void catalogCommaProseWithoutColonReturnsNeedsInput() {
    RequirementBrief brief =
        brief(
            "Pet Inventory Check",
            List.of("POST /pet-inventory/check"),
            "Check the Petstore inventory",
            List.of(
                httpEndpoint(
                    "trigger-1",
                    "Receive internal HTTP POST /pet-inventory/check.",
                    "POST",
                    "/pet-inventory/check",
                    ""),
                fact(
                    "c0875e786c4b8d657f8b792a68c029ef65fa6da6590a61fd8fafec3c65b40bc0",
                    RequirementFactKind.SERVICE_CALL,
                    "Call Petstore Ext catalog operation getInventory, GET /store/inventory.")),
            List.of(
                passThrough(
                    "map-init",
                    "trigger-1",
                    "c0875e786c4b8d657f8b792a68c029ef65fa6da6590a61fd8fafec3c65b40bc0")));

    BriefFlowExtractor.ExtractionResult.NeedsInput needsInput =
        assertInstanceOf(
            BriefFlowExtractor.ExtractionResult.NeedsInput.class, extractor.extract(brief));
    assertTrue(
        needsInput.missingFacts().stream().anyMatch(m -> m.contains("SERVICE_CALL.participant")),
        () -> needsInput.missingFacts().toString());
  }

  @Test
  void assignsMappingIdWhenRequirementBriefLeavesItBlank() {
    RequirementBrief brief =
        brief(
            "Pets",
            List.of("HTTP GET /pets"),
            "List pets",
            List.of(
                httpEndpoint(
                    "trigger-1", "HTTP GET /pets findPets", "GET", "/pets", "findPets"),
                serviceCall("call-1", "List pets from Petstore Ext", "Petstore Ext", "GET /pets")),
            List.of(
                new RequirementDataMapping(
                    "",
                    RequirementDataMapping.Stage.INITIALIZATION,
                    "trigger-1",
                    "call-1",
                    RequirementDataMapping.Mode.EXPLICIT,
                    List.of(new RequirementDataMapping.Rule("$.id", "$.petId", null)),
                    List.of("trigger-1", "call-1"))));

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertFalse(flow.dataMappings().getFirst().mappingId().isBlank());
    assertDoesNotThrow(() -> new NormalizedDesignFlowValidator().validate(flow));
  }

  @Test
  void v2MappingIntentsProjectWithoutStageInference() {
    RequirementBrief brief =
        brief(
                "Pets",
                List.of("HTTP GET /pets"),
                "List pets",
                List.of(
                    httpEndpoint(
                        "trigger-1", "HTTP GET /pets findPets", "GET", "/pets", "findPets"),
                    serviceCall("call-1", "List pets from Petstore Ext", "Petstore Ext", "GET /pets")),
                List.of())
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-pet",
                        "trigger-1",
                        MappingPort.OUTPUT,
                        "call-1",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("$.id", "$.petId", null)))));

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertEquals(1, flow.dataMappings().size());
    assertEquals("map-pet", flow.dataMappings().getFirst().mappingId());
    assertEquals(NormalizedDesignFlow.MappingMode.EXPLICIT, flow.dataMappings().getFirst().mode());
    assertEquals("$.id", flow.dataMappings().getFirst().rules().getFirst().sourcePath());
    assertTrue(flow.connections().isEmpty(), flow.connections().toString());
    assertDoesNotThrow(() -> new NormalizedDesignFlowValidator().validate(flow));
  }

  @Test
  void unknownSchemasProduceDirectConnectionWithoutMappingRow() {
    RequirementBrief brief =
        brief(
            "Kafka pet lookup",
            List.of("topic: user/events", "operation: consumeUserEvent"),
            "Consume Kafka user events and look up a pet",
            List.of(
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
                    ""),
                serviceCall("call-1", "Look up a pet in Petstore Ext", "Petstore Ext", "getPetById")),
            List.of());

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertTrue(flow.dataMappings().isEmpty(), flow.dataMappings().toString());
    assertTrue(flow.transformations().isEmpty());
    assertEquals(1, flow.connections().size());
    assertEquals("step-trigger", flow.connections().getFirst().fromStepId());
    assertEquals(flow.steps().getFirst().stepId(), flow.connections().getFirst().toStepId());
    assertDoesNotThrow(() -> new NormalizedDesignFlowValidator().validate(flow));
  }

  @Test
  void legacyPassThroughMappingBecomesDirectConnection() {
    RequirementBrief brief =
        brief(
            "Kafka pet lookup",
            List.of("topic: user/events", "operation: consumeUserEvent"),
            "Consume Kafka user events and look up a pet",
            List.of(
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
                    ""),
                serviceCall("call-1", "Look up a pet in Petstore Ext", "Petstore Ext", "getPetById")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertEquals("kafka", flow.trigger().kind());
    assertEquals("user/events", flow.trigger().endpointOrTopic());
    assertEquals("consumeUserEvent", flow.trigger().operationName());
    assertTrue(flow.dataMappings().isEmpty(), flow.dataMappings().toString());
    assertEquals(1, flow.connections().size());
    assertEquals("step-trigger", flow.connections().getFirst().fromStepId());
    assertEquals(flow.steps().getFirst().stepId(), flow.connections().getFirst().toStepId());
  }

  @Test
  void kafkaTriggerWithCatalogServiceCallDoesNotDemandHttpPath() {
    RequirementBrief brief =
        brief(
            "Kafka pet lookup",
            List.of(
                "service: reg-fixture-kafka",
                "operation: consumeUserEvent",
                "topic: user/events",
                "service: Petstore Ext",
                "operationQuery: Petstore Ext: getPetById",
                "method: GET",
                "path: /pet/{petId}"),
            "Consume Kafka user events and look up a pet",
            List.of(
                kafkaEndpoint("trigger-1", "Consume user events", "consumeUserEvent", "user/events"),
                serviceCall("call-1", "Look up a pet in Petstore Ext", "Petstore Ext", "getPetById")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertEquals("kafka", flow.trigger().kind());
    assertEquals("user/events", flow.trigger().endpointOrTopic());
    assertEquals("consumeUserEvent", flow.trigger().operationName());
    assertEquals("Petstore Ext", flow.participants().get(1).displayName());
    assertEquals("getPetById", flow.steps().getFirst().operationQuery());
  }

  @Test
  void httpPathInsideAServiceCallFactDoesNotBecomeTheKafkaTrigger() {
    RequirementBrief brief =
        brief(
            "Kafka pet lookup",
            List.of("topic: user/events", "operation: consumeUserEvent"),
            "Consume Kafka then call Petstore",
            List.of(
                kafkaEndpoint("trigger-1", "Consume user events", "consumeUserEvent", "user/events"),
                new RequirementFact(
                    "call-1",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.SERVICE_CALL,
                    "http-service-call",
                    "Petstore Ext getPetById using GET /pet/{petId}",
                    "Petstore Ext",
                    "getPetById",
                    "",
                    "GET",
                    "/pet/{petId}")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertEquals("kafka", flow.trigger().kind());
    assertEquals("user/events", flow.trigger().endpointOrTopic());
    assertFalse("/pet/{petId}".equals(flow.trigger().endpointOrTopic()));
    assertEquals("getPetById", flow.steps().getFirst().operationQuery());
  }

  @Test
  void missingTriggerPathReturnsNeedsInput() {
    RequirementBrief brief =
        brief(
            "Pets",
            List.of(),
            "List pets",
            List.of(
                fact("trigger-1", RequirementFactKind.ENDPOINT, "async-api-trigger"),
                serviceCall("call-1", "Find pets in Petstore Ext", "Petstore Ext", "findPets")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult result = extractor.extract(brief);

    BriefFlowExtractor.ExtractionResult.NeedsInput needsInput =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.NeedsInput.class, result);
    assertTrue(
        needsInput.missingFacts().stream().anyMatch(m -> m.contains("ENDPOINT.path")),
        () -> needsInput.missingFacts().toString());
  }

  @Test
  void missingServiceCallParticipantReturnsNeedsInput() {
    RequirementBrief brief =
        brief(
            "Pets",
            List.of("HTTP GET /pets"),
            "List pets",
            List.of(
                httpEndpoint("trigger-1", "HTTP GET /pets", "GET", "/pets", ""),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "statement call-1")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult result = extractor.extract(brief);

    BriefFlowExtractor.ExtractionResult.NeedsInput needsInput =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.NeedsInput.class, result);
    assertTrue(
        needsInput.missingFacts().stream()
            .anyMatch(m -> m.contains("SERVICE_CALL.participant")),
        () -> needsInput.missingFacts().toString());
  }

  @Test
  void withMappingsRejectsScriptOnlyIdsWhenBriefRequiresServiceCall() {
    RequirementBrief brief =
        brief(
            "HealthProxy",
            List.of("HTTP GET /health-proxy"),
            "Call Petstore Ext getInventory and return inventory JSON",
            List.of(
                fact(
                    "trigger-1",
                    RequirementFactKind.ENDPOINT,
                    "HTTP GET /health-proxy"),
                fact(
                    "call-1",
                    RequirementFactKind.SERVICE_CALL,
                    "Petstore Ext: getInventory")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));
    NormalizedDesignFlow authored =
        new IdsDocumentParser()
            .parseFirstFlow(
                """
                ### Integration flow for CIP Chain - HealthProxy

                ```mermaid
                sequenceDiagram
                    autonumber
                    participant Client as Client
                    participant CIP as CIP Chain
                    Client->>CIP: GET /health-proxy
                    CIP-->>Client: 200 inventory JSON
                ```
                """);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> extractor.withMappings(brief, authored));

    assertTrue(
        thrown.getMessage().contains("missing required outbound service-call"),
        thrown.getMessage());
    assertTrue(thrown.getMessage().contains("sequence diagram"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("brief has 1"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("IDS has 0"), thrown.getMessage());
  }

  @Test
  void withMappingsProjectsOntoAuthoredServiceCallStepIds() {
    RequirementBrief brief =
        brief(
            "HealthProxy",
            List.of("HTTP GET /health-proxy"),
            "Call Petstore Ext getInventory",
            List.of(
                fact("trigger-1", RequirementFactKind.ENDPOINT, "HTTP GET /health-proxy"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "Petstore Ext: getInventory")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));
    NormalizedDesignFlow authored =
        new IdsDocumentParser()
            .parseFirstFlow(
                """
                ### Integration flow for CIP Chain - HealthProxy

                ```mermaid
                sequenceDiagram
                    autonumber
                    participant Client as Client
                    participant CIP as CIP Chain
                    participant Petstore as Petstore Ext
                    Client->>CIP: GET /health-proxy
                    CIP->>Petstore: GET /store/inventory
                    Petstore-->>CIP: inventory JSON
                    CIP-->>Client: inventory JSON
                ```
                """);

    NormalizedDesignFlow projected = extractor.withMappings(brief, authored);

    NormalizedDesignFlow.Step serviceCall =
        projected.steps().stream()
            .filter(step -> "service-call".equalsIgnoreCase(step.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("call-1", serviceCall.sourceFactIds().getFirst());
    assertTrue(projected.dataMappings().isEmpty(), projected.dataMappings().toString());
    assertEquals(1, projected.connections().size());
    assertEquals("step-trigger", projected.connections().getFirst().fromStepId());
    assertEquals(serviceCall.stepId(), projected.connections().getFirst().toStepId());
  }

  @Test
  void withMappingsDropsUnboundLeftoverRowsInsteadOfDumpingIntentRefs() {
    RequirementBrief brief =
        brief(
            "HealthProxy",
            List.of("HTTP GET /health-proxy"),
            "Call Petstore Ext getInventory",
            List.of(
                fact("trigger-1", RequirementFactKind.ENDPOINT, "HTTP GET /health-proxy"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "Petstore Ext: getInventory")),
            List.of(
                leftoverHashMapping(
                    RequirementDataMapping.Stage.INITIALIZATION,
                    "820d45e25846bb71f78bd5c219f72f87399d7c263d789990f551d38b675bc9e3",
                    "b96b0eeae09d098d4fd86aaa47ea807df24901586ae64f91542d242845b2271f"),
                leftoverHashMapping(
                    RequirementDataMapping.Stage.RESPONSE,
                    "b96b0eeae09d098d4fd86aaa47ea807df24901586ae64f91542d242845b2271f",
                    "b8598ee044e21b5e58941a3e896a1c10ed1f3e05c4f031bb743ff8efdcc3d791"),
                passThrough("map-init", "trigger-1", "call-1"),
                passThrough("map-resp", "call-1", "trigger-1")));
    NormalizedDesignFlow authored =
        new IdsDocumentParser()
            .parseFirstFlow(
                """
                ### Integration flow for CIP Chain - HealthProxy

                ```mermaid
                sequenceDiagram
                    autonumber
                    participant Client as Client
                    participant CIP as CIP Chain
                    participant Petstore as Petstore Ext
                    Client->>CIP: GET /health-proxy
                    CIP->>Petstore: GET /store/inventory
                    Petstore-->>CIP: inventory JSON
                    CIP-->>Client: inventory JSON
                ```
                """);

    NormalizedDesignFlow projected = extractor.withMappings(brief, authored);

    assertTrue(projected.dataMappings().isEmpty(), projected.dataMappings().toString());
    assertEquals(1, projected.connections().size());
    assertTrue(
        projected.connections().stream()
            .noneMatch(
                connection ->
                    connection.fromStepId().contains("820d45e2")
                        || connection.toStepId().contains("b96b0eea")),
        projected.connections().toString());
  }

  @Test
  void mapsServiceCallIdToGeneratedStepProvenance() {
    RequirementBrief brief =
        omWfmBrief(
            List.of(
                boundCall(
                    "call-om-result",
                    "fact-om",
                    "Order Management",
                    "onTaskResult",
                    "op-om"),
                boundCall(
                    "call-wfm-create-task",
                    "fact-wfm",
                    "Salesforce WFM",
                    "createTask",
                    "op-wfm")));

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertEquals(List.of("call-om-result", "fact-om"), flow.steps().get(0).sourceFactIds());
    assertEquals("onTaskResult", flow.steps().get(0).operationQuery());
    assertEquals(
        List.of("call-wfm-create-task", "fact-wfm"), flow.steps().get(1).sourceFactIds());
    assertEquals("createTask", flow.steps().get(1).operationQuery());
  }

  @Test
  void reorderingCallsDoesNotSwapBindings() {
    RequirementServiceCall om =
        boundCall("call-om-result", "fact-om", "Order Management", "onTaskResult", "op-om");
    RequirementServiceCall wfm =
        boundCall(
            "call-wfm-create-task", "fact-wfm", "Salesforce WFM", "createTask", "op-wfm");
    RequirementBrief reordered = omWfmBrief(List.of(wfm, om));

    NormalizedDesignFlow flow =
        assertInstanceOf(
                BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(reordered))
            .flow();

    assertEquals("call-wfm-create-task", flow.steps().get(0).sourceFactIds().getFirst());
    assertEquals("createTask", flow.steps().get(0).operationQuery());
    assertEquals("call-om-result", flow.steps().get(1).sourceFactIds().getFirst());
    assertEquals("onTaskResult", flow.steps().get(1).operationQuery());
  }

  @Test
  void mapsDuplicateOperationsToDistinctSteps() {
    RequirementBrief brief =
        brief(
                "OM twice",
                List.of("HTTP POST /tasks"),
                "Call onTaskResult twice",
                List.of(
                    httpEndpoint("trigger-1", "HTTP POST /tasks", "POST", "/tasks", ""),
                    serviceCallFact(
                        "fact-om", "call-om-result", "Order Management", "onTaskResult"),
                    serviceCallFact(
                        "fact-om-again", "call-om-again", "Order Management", "onTaskResult")),
                List.of())
            .withServiceCalls(
                List.of(
                    boundCall(
                        "call-om-result",
                        "fact-om",
                        "Order Management",
                        "onTaskResult",
                        "op-shared"),
                    boundCall(
                        "call-om-again",
                        "fact-om-again",
                        "Order Management",
                        "onTaskResult",
                        "op-shared")));

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, extractor.extract(brief))
            .flow();

    assertEquals(2, flow.steps().size());
    assertEquals("call-om-result", flow.steps().get(0).sourceFactIds().getFirst());
    assertEquals("call-om-again", flow.steps().get(1).sourceFactIds().getFirst());
    assertEquals("onTaskResult", flow.steps().get(0).operationQuery());
    assertEquals("onTaskResult", flow.steps().get(1).operationQuery());
  }

  @Test
  void withMappingsMatchesAuthoredStepsByServiceCallIdNotPosition() {
    RequirementBrief brief =
        omWfmBrief(
            List.of(
                boundCall(
                    "call-om-result",
                    "fact-om",
                    "Order Management",
                    "onTaskResult",
                    "op-om"),
                boundCall(
                    "call-wfm-create-task",
                    "fact-wfm",
                    "Salesforce WFM",
                    "createTask",
                    "op-wfm")));
    NormalizedDesignFlow authored =
        new NormalizedDesignFlow(
            "1",
            "flow-1",
            "OM to Salesforce WFM",
            "",
            new NormalizedDesignFlow.Trigger(
                "http", "client", "HTTP", "/tasks", "POST", List.of("trigger-1")),
            List.of(
                new NormalizedDesignFlow.Participant(
                    "client", "Client", "EXTERNAL", List.of("trigger-1")),
                new NormalizedDesignFlow.Participant(
                    "wfm", "Salesforce WFM", "EXTERNAL", List.of("call-wfm-create-task")),
                new NormalizedDesignFlow.Participant(
                    "om", "Order Management", "EXTERNAL", List.of("call-om-result"))),
            List.of(
                new NormalizedDesignFlow.Step(
                    "step-wfm",
                    "service-call",
                    "client",
                    "wfm",
                    "createTask",
                    "",
                    List.of("call-wfm-create-task", "fact-wfm")),
                new NormalizedDesignFlow.Step(
                    "step-om",
                    "service-call",
                    "client",
                    "om",
                    "onTaskResult",
                    "",
                    List.of("call-om-result", "fact-om"))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    NormalizedDesignFlow projected = extractor.withMappings(brief, authored);

    assertEquals("step-trigger", projected.connections().getFirst().fromStepId());
    assertEquals("step-om", projected.connections().getFirst().toStepId());
    assertEquals("step-om", projected.connections().get(1).fromStepId());
    assertEquals("step-wfm", projected.connections().get(1).toStepId());
  }

  @Test
  void scriptOnlyBriefDoesNotRequireServiceCall() {
    RequirementBrief brief =
        new RequirementBrief(
            "Greetings",
            List.of("GET request on internal route \"/greetings\""),
            List.of("No service calls", "No APIHub"),
            List.of(),
            List.of(),
            "Chain receives GET on /greetings and returns a greeting from a script.",
            "draft-1",
            "draft",
            List.of(
                httpEndpoint("trigger-1", "GET /greetings", "GET", "/greetings", ""),
                fact(
                    "behavior-script",
                    RequirementFactKind.BEHAVIOR,
                    "Chain returns a greeting from a QIP script element."),
                new RequirementFact(
                    "neg-service",
                    RequirementFactPolarity.NEGATIVE,
                    RequirementFactKind.CONSTRAINT,
                    "service-call",
                    "No service calls."),
                new RequirementFact(
                    "neg-apihub",
                    RequirementFactPolarity.NEGATIVE,
                    RequirementFactKind.CONSTRAINT,
                    "apihub",
                    "No APIHub.")),
            List.of());

    BriefFlowExtractor.ExtractionResult result = extractor.extract(brief);

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, result).flow();
    assertEquals("/greetings", flow.trigger().endpointOrTopic());
    assertEquals("GET", flow.trigger().operationName());
    assertEquals(1, flow.steps().size());
    assertEquals("script", flow.steps().getFirst().kind());
    assertTrue(
        flow.steps().stream().noneMatch(step -> "service-call".equalsIgnoreCase(step.kind())));
  }

  @Test
  void scriptOnlyEndpointBriefWithoutBehaviorSynthesizesScriptStep() {
    RequirementBrief brief =
        new RequirementBrief(
            "Create a dual-trigger integration chain for greeting responses.",
            List.of("HTTP GET on internal route \"/greetings\"", "Quartz-scheduler hourly cron"),
            List.of("No service calls", "No APIHub"),
            List.of(),
            List.of(),
            "HTTP GET /greetings and quartz-scheduler connect to the same script returning a"
                + " plain-text greeting.",
            "draft-1",
            "draft",
            List.of(
                httpEndpoint("endpoint-http", "HTTP GET /greetings", "GET", "/greetings", ""),
                fact("endpoint-quartz", RequirementFactKind.ENDPOINT, "Quartz-scheduler hourly cron"),
                new RequirementFact(
                    "neg-service",
                    RequirementFactPolarity.NEGATIVE,
                    RequirementFactKind.CONSTRAINT,
                    "service-call",
                    "No service calls, error handling, MCP, chain failure handler, or APIHub.")),
            List.of());

    BriefFlowExtractor.ExtractionResult result = extractor.extract(brief);

    NormalizedDesignFlow flow =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.Complete.class, result).flow();
    assertEquals("/greetings", flow.trigger().endpointOrTopic());
    assertEquals("GET", flow.trigger().operationName());
    assertEquals(1, flow.steps().size());
    assertEquals("script", flow.steps().getFirst().kind());
    assertTrue(flow.steps().getFirst().operationQuery().toLowerCase().contains("script"));
  }

  private static RequirementBrief ordersBrief() {
    return brief(
        "Orders",
        List.of("HTTP POST /orders"),
        "Create order",
        List.of(
            httpEndpoint(
                "fact-trigger", "HTTP POST /orders createOrder", "POST", "/orders", "createOrder"),
            serviceCall("fact-step", "Create an order via Orders API", "Orders API", "create order"),
            fact("fact-p", RequirementFactKind.BEHAVIOR, "behavior"),
            fact("fact-map", RequirementFactKind.BEHAVIOR, "mapping")),
        List.of(passThrough("map-1", "fact-trigger", "fact-step", "fact-map")));
  }

  private static RequirementBrief brief(
      String goal,
      List<String> inputs,
      String summary,
      List<RequirementFact> facts,
      List<RequirementDataMapping> mappings) {
    return new RequirementBrief(
        goal, inputs, List.of(), List.of(), List.of(), summary, "draft-1", "draft", facts, mappings);
  }

  private static RequirementFact fact(String id, RequirementFactKind kind, String text) {
    return new RequirementFact(id, RequirementFactPolarity.POSITIVE, kind, null, text);
  }

  private static RequirementFact httpEndpoint(
      String id, String text, String httpMethod, String path, String operation) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT,
        "http-trigger",
        text,
        "",
        operation,
        "",
        httpMethod,
        path);
  }

  private static RequirementFact kafkaEndpoint(
      String id, String text, String operation, String topic) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT,
        "kafka-trigger-2",
        text,
        "",
        operation,
        topic,
        "",
        "");
  }

  private static RequirementFact serviceCall(
      String id, String text, String participant, String operation) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "http-service-call",
        text,
        participant,
        operation,
        "",
        "",
        "");
  }

  private static RequirementBrief omWfmBrief(List<RequirementServiceCall> serviceCalls) {
    return brief(
            "OM to Salesforce WFM",
            List.of("HTTP POST /tasks"),
            "Call OM then Salesforce WFM",
            List.of(
                httpEndpoint("trigger-1", "HTTP POST /tasks", "POST", "/tasks", ""),
                serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult"),
                serviceCallFact(
                    "fact-wfm", "call-wfm-create-task", "Salesforce WFM", "createTask")),
            List.of())
        .withServiceCalls(serviceCalls);
  }

  private static RequirementFact serviceCallFact(
      String sourceFactId, String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        sourceFactId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "http-service-call",
        "Call " + participant + " " + operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static RequirementServiceCall boundCall(
      String serviceCallId,
      String sourceFactId,
      String participant,
      String operation,
      String integrationOperationId) {
    CatalogBindingHint hint =
        new CatalogBindingHint(
            "2",
            serviceCallId,
            sourceFactId,
            operation,
            "sys-" + serviceCallId,
            "sg-" + serviceCallId,
            "spec-" + serviceCallId,
            integrationOperationId,
            "http",
            "POST",
            "/tasks",
            "2024.4",
            Instant.parse("2026-08-27T12:00:00Z"),
            "evidence-" + serviceCallId);
    return new RequirementServiceCall(
        serviceCallId, sourceFactId, participant, operation, hint);
  }

  private static RequirementDataMapping leftoverHashMapping(
      RequirementDataMapping.Stage stage, String from, String to) {
    return new RequirementDataMapping(
        "",
        stage,
        from,
        to,
        RequirementDataMapping.Mode.PASS_THROUGH,
        List.of(),
        List.of("leftover-fact"));
  }

  private static RequirementDataMapping passThrough(String id, String from, String to) {
    return passThrough(id, from, to, id);
  }

  private static RequirementDataMapping passThrough(
      String id, String from, String to, String sourceFactId) {
    return new RequirementDataMapping(
        id,
        RequirementDataMapping.Stage.INITIALIZATION,
        from,
        to,
        RequirementDataMapping.Mode.PASS_THROUGH,
        List.of(),
        List.of(sourceFactId));
  }
}
