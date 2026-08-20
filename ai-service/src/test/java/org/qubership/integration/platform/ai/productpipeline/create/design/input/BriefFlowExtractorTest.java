package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

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
                fact("trigger-1", RequirementFactKind.ENDPOINT, "HTTP GET /pets findPets"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "Petstore Ext: GET /pets")),
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
                fact(
                    "trigger-1",
                    RequirementFactKind.ENDPOINT,
                    "HTTP POST /demo/pet-inventory/check"),
                fact(
                    "call-1",
                    RequirementFactKind.SERVICE_CALL,
                    "Petstore Ext: GET /store/inventory"),
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
  void missingTriggerPathReturnsNeedsInput() {
    RequirementBrief brief =
        brief(
            "Pets",
            List.of(),
            "List pets",
            List.of(
                fact("trigger-1", RequirementFactKind.ENDPOINT, "async-api-trigger"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "Petstore Ext: findPets")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult result = extractor.extract(brief);

    BriefFlowExtractor.ExtractionResult.NeedsInput needsInput =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.NeedsInput.class, result);
    assertTrue(
        needsInput.missingFacts().stream().anyMatch(m -> m.contains("trigger path")),
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
                fact("trigger-1", RequirementFactKind.ENDPOINT, "HTTP GET /pets"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "statement call-1")),
            List.of(passThrough("map-init", "trigger-1", "call-1")));

    BriefFlowExtractor.ExtractionResult result = extractor.extract(brief);

    BriefFlowExtractor.ExtractionResult.NeedsInput needsInput =
        assertInstanceOf(BriefFlowExtractor.ExtractionResult.NeedsInput.class, result);
    assertTrue(
        needsInput.missingFacts().stream()
            .anyMatch(m -> m.contains("SERVICE_CALL participant")),
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
        authored.steps().stream()
            .filter(step -> "service-call".equalsIgnoreCase(step.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals(1, projected.dataMappings().size());
    assertEquals("step-trigger", projected.dataMappings().getFirst().fromStepId());
    assertEquals(serviceCall.stepId(), projected.dataMappings().getFirst().toStepId());
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

    assertEquals(2, projected.dataMappings().size());
    assertTrue(
        projected.dataMappings().stream()
            .noneMatch(
                mapping ->
                    mapping.fromStepId().contains("820d45e2")
                        || mapping.toStepId().contains("b96b0eea")),
        projected.dataMappings().toString());
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
                fact(
                    "behavior-trigger",
                    RequirementFactKind.BEHAVIOR,
                    "Chain receives GET on internal route \"/greetings\"."),
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
                fact(
                    "endpoint-http",
                    RequirementFactKind.ENDPOINT,
                    "HTTP GET on internal route \"/greetings\""),
                fact("endpoint-quartz", RequirementFactKind.ENDPOINT, "Quartz-scheduler hourly cron"),
                new RequirementFact(
                    "neg-service",
                    RequirementFactPolarity.NEGATIVE,
                    RequirementFactKind.CONSTRAINT,
                    "",
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
            fact("fact-trigger", RequirementFactKind.ENDPOINT, "HTTP POST /orders createOrder"),
            fact("fact-step", RequirementFactKind.SERVICE_CALL, "Orders API: create order"),
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
