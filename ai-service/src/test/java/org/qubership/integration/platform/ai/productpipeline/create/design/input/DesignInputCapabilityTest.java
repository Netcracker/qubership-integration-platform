package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.llm.agent.DesignInputPromptAgent;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

class DesignInputCapabilityTest {

  private static final String VALID_IDS =
      """
      # Integration Design Specification

      ## Integration Process

      ### Integration flow for CIP Chain - Orders

      ```mermaid
      sequenceDiagram
          autonumber
          participant Client as Client
          participant Orders as Orders API
          Client->>Orders: create order
      ```

      ### Integration flow for CIP Chain - Ignored Second

      ```mermaid
      sequenceDiagram
          autonumber
          participant A as A
          participant B as B
          A->>B: ignored
      ```
      """;

  private static final String SCRIPT_ONLY_HEALTHPROXY_IDS =
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
      """;

  private static final String HEALTHPROXY_WITH_SERVICE_CALL_IDS =
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
          CIP-->>Client: 200 inventory JSON
      ```
      """;

  private static final String FLOWCHART_IDS =
      """
      ### Integration flow for CIP Chain - Orders

      ```mermaid
      flowchart TD
          A --> B
      ```
      """;

  @Test
  void sharedCapabilitySelectsEnterRouteAndPrepareDesignByStageId() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    StageOutcome entry = outcome(capability, context("ids-entry", Map.of("userText", VALID_IDS)));
    assertEquals(StageOutcomeClass.SUCCEEDED, entry.outcomeClass());
    assertTrue(
        entry.candidates().stream().anyMatch(c -> c.kind() == Kind.DESIGN_ENTRY_ROUTE));

    StageOutcome unsupported = outcome(capability, context("other-stage", Map.of()));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, unsupported.outcomeClass());
  }

  @Test
  void provideModeSucceedsWithIdsDocumentAndNormalizedFlowNeverCandidate() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    StageOutcome entry = outcome(capability, context("ids-entry", Map.of("userText", VALID_IDS)));
    assertEquals(DesignEntryRoute.PROVIDE, routePayload(entry));

    IdsDocument provided =
        entry.candidates().stream()
            .filter(c -> c.kind() == Kind.IDS_DOCUMENT)
            .map(c -> (IdsDocument) c.payload())
            .findFirst()
            .orElseThrow();

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.PROVIDE,
                    "idsDocument",
                    provided)));
    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(
        Set.of(Kind.DESIGN_MODE, Kind.IDS_DOCUMENT, Kind.NORMALIZED_DESIGN_FLOW),
        kinds(prepared));
    assertEquals(DesignMode.PROVIDE, modePayload(prepared));
    assertEquals("Orders", flowPayload(prepared).chainName());
  }

  @Test
  void secondIntegrationFlowIsIgnored() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    StageOutcome entry = outcome(capability, context("ids-entry", Map.of("userText", VALID_IDS)));
    IdsDocument provided =
        entry.candidates().stream()
            .filter(c -> c.kind() == Kind.IDS_DOCUMENT)
            .map(c -> (IdsDocument) c.payload())
            .findFirst()
            .orElseThrow();
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute", DesignEntryRoute.PROVIDE, "idsDocument", provided)));
    assertEquals("Orders", flowPayload(prepared).chainName());
  }

  @Test
  void unsupportedMermaidReturnsValidationFailure() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    StageOutcome entry =
        outcome(capability, context("ids-entry", Map.of("userText", FLOWCHART_IDS)));
    IdsDocument provided =
        entry.candidates().stream()
            .filter(c -> c.kind() == Kind.IDS_DOCUMENT)
            .map(c -> (IdsDocument) c.payload())
            .findFirst()
            .orElseThrow();
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute", DesignEntryRoute.PROVIDE, "idsDocument", provided)));
    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, prepared.outcomeClass());
  }

  @Test
  void generateReturnsOneCandidateBatchWithModeIdsAndFlow() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Generate full IDS",
                    "requirementBrief",
                    approvedBriefWithMappings())));
    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass());
    assertEquals(
        Set.of(Kind.DESIGN_MODE, Kind.IDS_DOCUMENT, Kind.NORMALIZED_DESIGN_FLOW),
        kinds(prepared));
    assertEquals(DesignMode.GENERATE, modePayload(prepared));
    assertEquals(IdsDocument.Mode.GENERATED, idsPayload(prepared).mode());
    assertEquals("Orders", flowPayload(prepared).chainName());
  }


  @Test
  void scriptOnlyIncompletePassThroughDoesNotBlockGenerate() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief scriptOnlyWithJunk =
        new RequirementBrief(
            "Greetings",
            List.of("HTTP GET /greetings"),
            List.of(),
            List.of(),
            List.of(),
            "Return greeting text from a script",
            "draft-1",
            "draft",
            List.of(
                fact(
                    "trigger-1",
                    RequirementFactKind.ENDPOINT,
                    "http-trigger",
                    "HTTP GET /greetings"),
                fact(
                    "script-1",
                    RequirementFactKind.BEHAVIOR,
                    "script",
                    "Return greeting text from a script")),
            List.of(shapelessPassThrough()));

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Generate full IDS",
                    "requirementBrief",
                    scriptOnlyWithJunk)));

    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass());
    assertFalse(
        prepared.message() != null && prepared.message().contains("dataMapping stage is required"),
        prepared.message());
  }

  @Test
  void generateDoesNotWaitWhenHealthProxyServiceCallMappingsAreMissing() {
    DesignInputCapability capability =
        capabilityWithFixedGenerate(HEALTHPROXY_WITH_SERVICE_CALL_IDS);

    StageOutcome emptyMappings =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Generate full IDS",
                    "requirementBrief",
                    healthProxyBrief(List.of()))));
    assertContinuesWithPassThroughEdges(emptyMappings, StageOutcomeClass.CANDIDATE);

    StageOutcome leftoverMappings =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Generate full IDS",
                    "requirementBrief",
                    healthProxyBrief(unboundLeftoverMappings()))));
    assertContinuesWithPassThroughEdges(leftoverMappings, StageOutcomeClass.CANDIDATE);
  }

  @Test
  void skipDoesNotWaitWhenHealthProxyServiceCallMappingsAreMissing() {
    DesignInputCapability capability =
        capabilityWithFixedGenerate(HEALTHPROXY_WITH_SERVICE_CALL_IDS);

    StageOutcome emptyMappings =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "no",
                    "requirementBrief",
                    healthProxyBrief(List.of()))));
    assertContinuesWithPassThroughEdges(emptyMappings, StageOutcomeClass.SUCCEEDED);

    StageOutcome leftoverMappings =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "no",
                    "requirementBrief",
                    healthProxyBrief(unboundLeftoverMappings()))));
    assertContinuesWithPassThroughEdges(leftoverMappings, StageOutcomeClass.SUCCEEDED);
  }

  @Test
  void missingMappingIntentDefaultsToPassThrough() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief briefWithoutMappings =
        new RequirementBrief(
            "Orders",
            List.of("HTTP POST /orders"),
            List.of(),
            List.of(),
            List.of(),
            "Create order",
            "draft-1",
            "draft",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP POST /orders createOrder",
                    "POST",
                    "/orders",
                    "createOrder"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Create an order via Orders API",
                    "Orders API",
                    "create order")),
            List.of());
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    briefWithoutMappings)));
    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(DesignMode.DERIVE, modePayload(prepared));
    assertDirectPassThrough(flowPayload(prepared));
  }

  @Test
  void unresolvedRequiredTargetKeepsBriefInNeedsInput() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief brief =
        healthProxyBrief(List.of())
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-init",
                        "trigger-1",
                        MappingPort.OUTPUT,
                        "call-1",
                        MappingPort.REQUEST,
                        List.of(
                            new MappingIntentRule(
                                "", "$.personId", null, MappingRuleStatus.UNRESOLVED)))));
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    brief)));
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertTrue(prepared.message().contains("$.personId"), prepared.message());
  }

  @Test
  void optionalUnmatchedTargetDoesNotBlockDesignInput() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief brief =
        healthProxyBrief(List.of())
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-init",
                        "trigger-1",
                        MappingPort.OUTPUT,
                        "call-1",
                        MappingPort.REQUEST,
                        List.of(
                            new MappingIntentRule(
                                "$.orderId", "$.orderId", null, MappingRuleStatus.AUTO),
                            new MappingIntentRule(
                                "$.userId", "$.personId", null, MappingRuleStatus.PROPOSED)))));
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    brief)));
    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(DesignMode.DERIVE, modePayload(prepared));
  }

  @Test
  void shapelessLeftoverRowsDefaultToPassThroughOnServiceCallBrief() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief briefWithShapelessLeftovers =
        new RequirementBrief(
            "Orders",
            List.of("HTTP POST /orders"),
            List.of(),
            List.of(),
            List.of(),
            "Create order",
            "draft-1",
            "draft",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP POST /orders createOrder",
                    "POST",
                    "/orders",
                    "createOrder"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Create an order via Orders API",
                    "Orders API",
                    "create order")),
            List.of(shapelessPassThrough()));
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    briefWithShapelessLeftovers)));
    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(DesignMode.DERIVE, modePayload(prepared));
    assertDirectPassThrough(flowPayload(prepared));
    assertFalse(
        prepared.message() != null && prepared.message().contains("dataMapping stage is required"),
        prepared.message());
  }

  @Test
  void idsChoiceTextContainingAnArrowDoesNotBecomeAnExplicitMapping() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief briefWithoutMappings =
        new RequirementBrief(
            "Orders",
            List.of("HTTP POST /orders"),
            List.of(),
            List.of(),
            List.of(),
            "Create order",
            "draft-1",
            "draft",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP POST /orders createOrder",
                    "POST",
                    "/orders",
                    "createOrder"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Create an order via Orders API",
                    "Orders API",
                    "create order")),
            List.of());

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR,
                    DesignMode.DERIVE,
                    "userText",
                    "Derive minimal IDS for endpoint -> service",
                    "requirementBrief",
                    briefWithoutMappings)));

    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertDirectPassThrough(flowPayload(prepared));
  }

  @Test
  void mappingRuleSyntaxDoesNotInventStageRowsOnAPassThroughBrief() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief briefWithoutMappings =
        new RequirementBrief(
            "Orders",
            List.of("HTTP POST /orders"),
            List.of(),
            List.of(),
            List.of(),
            "Create order",
            "draft-1",
            "draft",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP POST /orders createOrder",
                    "POST",
                    "/orders",
                    "createOrder"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Create an order via Orders API",
                    "Orders API",
                    "create order")),
            List.of());

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR,
                    DesignMode.GENERATE,
                    "userText",
                    "1: $.request.id -> $.headers.X-Request-Id\n2: $.inventory -> $.body",
                    "requirementBrief",
                    briefWithoutMappings)));

    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass(), prepared.message());
    assertDirectPassThrough(flowPayload(prepared));
  }

  @Test
  void authoringPromptContainsTypedMappingRules() {
    RequirementBrief brief =
        new RequirementBrief(
            "Orders",
            List.of("HTTP POST /orders"),
            List.of(),
            List.of(),
            List.of(),
            "Create order",
            "draft-1",
            "draft",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP POST /orders createOrder",
                    "POST",
                    "/orders",
                    "createOrder"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Create an order via Orders API",
                    "Orders API",
                    "create order")),
            List.of(
                new RequirementDataMapping(
                    "map-init",
                    RequirementDataMapping.Stage.INITIALIZATION,
                    "trigger-1",
                    "call-1",
                    RequirementDataMapping.Mode.EXPLICIT,
                    List.of(new RequirementDataMapping.Rule("$.id", "$.customerId", null)),
                    List.of("fact-map"))));

    String prompt = DesignInputCapability.authoringPrompt(brief);

    assertTrue(prompt.contains("map-init [INITIALIZATION, EXPLICIT]"), prompt);
    assertTrue(prompt.contains("$.id -> $.customerId"), prompt);
  }

  @Test
  void generateContinuesAfterPassThroughMappingConfirmation() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    RequirementBrief briefWithoutMappings =
        new RequirementBrief(
            "Orders",
            List.of("HTTP POST /orders"),
            List.of(),
            List.of(),
            List.of(),
            "Create order",
            "draft-1",
            "draft",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP POST /orders createOrder",
                    "POST",
                    "/orders",
                    "createOrder"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Create an order via Orders API",
                    "Orders API",
                    "create order")),
            List.of());
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR,
                    DesignMode.GENERATE,
                    "userText",
                    "PASS_THROUGH",
                    "requirementBrief",
                    briefWithoutMappings)));
    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass());
    assertEquals(DesignMode.GENERATE, modePayload(prepared));
  }

  @Test
  void passThroughWithUnboundLeftoverMappingsDoesNotDumpIntentRefs() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR,
                    DesignMode.GENERATE,
                    "userText",
                    "pass_through",
                    "requirementBrief",
                    briefWithUnboundLeftoverMappings())));

    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass());
    assertEquals(DesignMode.GENERATE, modePayload(prepared));
    assertDirectPassThrough(flowPayload(prepared));
    assertFalse(containsIntentRefDump(prepared.message()), prepared.message());
  }

  @Test
  void skipIdsWithUnboundLeftoverMappingsContinuesWithoutOverlayDump() {
    DesignInputCapability capability = capabilityWithFixedGenerate(VALID_IDS);
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "no",
                    "requirementBrief",
                    briefWithUnboundLeftoverMappings())));

    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(DesignMode.DERIVE, modePayload(prepared));
    assertDirectPassThrough(flowPayload(prepared));
    assertFalse(containsIntentRefDump(prepared.message()), prepared.message());
  }

  @Test
  void skipIdsDerivesRequiredServiceCallFromTheBrief() {
    DesignInputCapability capability = capabilityWithFixedGenerate(SCRIPT_ONLY_HEALTHPROXY_IDS);
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "no",
                    "requirementBrief",
                    briefWithUnboundLeftoverMappings())));

    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(DesignMode.DERIVE, modePayload(prepared));
    assertEquals(
        1,
        flowPayload(prepared)
            .steps()
            .stream()
            .filter(step -> "service-call".equals(step.kind()))
            .count());
    assertFalse(containsIntentRefDump(prepared.message()), prepared.message());
  }

  @Test
  void authoringWaitRewritesIntentRefDumpsIntoPlainLanguage() {
    String rewritten =
        DesignInputCapability.userFacingAuthoringWait(
            "mapping  intent refs 820d45e25846bb71f78bd5c219f72f87399d7c263d789990f551d38b675bc9e3"
                + " → b96b0eeae09d098d4fd86aaa47ea807df24901586ae64f91542d242845b2271f");
    assertFalse(containsIntentRefDump(rewritten), rewritten);
    assertTrue(rewritten.toLowerCase().contains("pass through"), rewritten);
  }

  @Test
  void russianBriefSurfacesIdsPathChoiceWithoutLocaleHardcoding() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    RequirementBrief russianBrief =
        new RequirementBrief(
            "Orders",
            List.of("HTTP POST /orders"),
            List.of(),
            List.of(),
            List.of(),
            "Create order via integration",
            "draft-1",
            "draft text in user language may appear here",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP POST /orders createOrder",
                    "POST",
                    "/orders",
                    "createOrder"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Create an order via Orders API",
                    "Orders API",
                    "create order")),
            List.of());
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "requirementBrief",
                    russianBrief)));
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertTrue(prepared.message() != null && !prepared.message().isBlank(), prepared.message());
  }

  @Test
  void standardRouteWaitsForExplicitIdsChoice() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "requirementBrief",
                    approvedBriefWithMappings())));
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertTrue(
        prepared.message() != null && !prepared.message().isBlank(), prepared.message());
    assertTrue(
        prepared.message().toLowerCase(java.util.Locale.ROOT).contains("integration design")
            || prepared.message().toLowerCase(java.util.Locale.ROOT).contains("ids"),
        prepared.message());
  }

  @Test
  void authoredIdsChoiceCannotOverrideItsGateMarker() {
    DesignInputCapability capability =
        new DesignInputCapability(
            new IdsDocumentParser(),
            new NormalizedDesignFlowValidator(),
            new MinimalIdsRenderer(),
            new BriefFlowExtractor(),
            new DesignRequirementBriefCoverageValidator(),
            (brief, repairNote) -> "unused",
            DesignInputIdsPathPrompts.withFixedPrompts(
                ignored -> "__GATE:stage-revise__Choose an IDS path.", ignored -> "unused"));

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "requirementBrief",
                    approvedBriefWithMappings())));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertEquals(PipelineGates.IDS_PATH_CHOICE, PipelineGates.gateOf(prepared.message()).orElseThrow());
    assertEquals("Choose an IDS path.", PipelineGates.strip(prepared.message()));
  }

  @Test
  void idsEntryEmitsStandardRouteWithoutIdsDocument() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    StageOutcome entry =
        outcome(
            capability,
            context("ids-entry", Map.of("userText", "Create an orders HTTP integration")));
    assertEquals(StageOutcomeClass.SUCCEEDED, entry.outcomeClass());
    assertEquals(DesignEntryRoute.STANDARD, routePayload(entry));
    assertTrue(entry.candidates().stream().noneMatch(c -> c.kind() == Kind.IDS_DOCUMENT));
  }


  @Test
  void staleDiscoveryUserTextDoesNotSkipIdsPathEvenIfClassifierWouldDerive() {
    DesignInputPromptAgent alwaysDerive =
        new DesignInputPromptAgent() {
          @Override
          public String askIdsPathChoice(String responseLocale, String reference) {
            return "LLM IDS path choice in conversation language";
          }

          @Override
          public String askMappingGap(
              String responseLocale, String reference, String missingEdges, String pendingMode) {
            return "LLM mapping ask";
          }

          @Override
          public String classifyIdsPathChoice(String userText) {
            return "DERIVE";
          }

          @Override
          public String classifyMappingReply(String userText) {
            return "NONE";
          }
        };
    DesignInputCapability capability =
        new DesignInputCapability(
            new IdsDocumentParser(),
            new NormalizedDesignFlowValidator(),
            new MinimalIdsRenderer(),
            new BriefFlowExtractor(),
            new DesignRequirementBriefCoverageValidator(),
            (brief, repairNote) -> "unused",
            new DesignInputIdsPathPrompts(alwaysDerive));
    RequirementBrief briefWithoutServiceCall =
        new RequirementBrief(
            "Greetings",
            List.of("HTTP GET /greetings"),
            List.of(),
            List.of(),
            List.of(),
            "Return greeting text from a script",
            "draft-1",
            "draft",
            List.of(
                fact(
                    "trigger-1",
                    RequirementFactKind.ENDPOINT,
                    "http-trigger",
                    "HTTP GET /greetings")),
            List.of());
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Create HTTP GET /greetings that returns greeting text",
                    "discoveryUserText",
                    "Create HTTP GET /greetings that returns greeting text",
                    "requirementBrief",
                    briefWithoutServiceCall)));
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertFalse(
        prepared.message().contains("SERVICE_CALL process step"), prepared.message());
    assertTrue(
        prepared.message().contains("LLM IDS path choice"), prepared.message());
  }

  @Test
  void deriveRendersTheApprovedBriefWithoutInvokingTheGenerator() {
    AtomicInteger generatorCalls = new AtomicInteger();
    DesignInputCapability capability =
        new DesignInputCapability(
            new IdsDocumentParser(),
            new NormalizedDesignFlowValidator(),
            new MinimalIdsRenderer(),
            new BriefFlowExtractor(),
            new DesignRequirementBriefCoverageValidator(),
            (brief, repairNote) -> {
              generatorCalls.incrementAndGet();
              return VALID_IDS;
            });
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "no",
                    "requirementBrief",
                    deriveBrief())));

    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(DesignMode.DERIVE, modePayload(prepared));
    assertEquals(IdsDocument.Mode.DERIVED, idsPayload(prepared).mode());
    assertEquals(MinimalIdsRenderer.RENDERER_VERSION, idsPayload(prepared).rendererVersion());
    assertEquals(0, generatorCalls.get());
    assertTrue(idsPayload(prepared).markdown().contains("create order"));
  }

  @Test
  void deriveTreatsMissingApprovedBriefFactsAsARecoverableValidationFailure() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    RequirementBrief brief =
        new RequirementBrief(
            "Pets",
            List.of("HTTP GET /pets"),
            List.of(),
            List.of(),
            List.of(),
            "List pets",
            "draft-1",
            "draft",
            List.of(
                httpTrigger(
                    "trigger-1",
                    "http-trigger",
                    "HTTP GET /pets",
                    "GET",
                    "/pets",
                    ""),
                fact(
                    "call-1",
                    RequirementFactKind.SERVICE_CALL,
                    null,
                    "Call Petstore Ext operation getPetById using petId mapped from Kafka userId")),
            List.of(
                mapping(
                    "map-init",
                    RequirementDataMapping.Stage.INITIALIZATION,
                    "trigger-1",
                    "call-1",
                    RequirementDataMapping.Mode.PASS_THROUGH,
                    List.of("fact-map"))));

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    DesignInputIdsPathPrompts.PENDING_DESIGN_MODE_ATTR,
                    DesignMode.DERIVE,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    brief)));

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, prepared.outcomeClass());
    assertTrue(prepared.message().contains("approved requirement brief"), prepared.message());
    assertTrue(prepared.message().contains("SERVICE_CALL.participant"), prepared.message());
  }

  /**
   * A document the parser cannot read is repaired, not surfaced to the caller.
   *
   * <p>The heading and the sequence diagram are asked for in prose, so the author misses them from
   * time to time. Nobody downstream can supply what the author left out, which makes a question
   * the wrong move and a rewrite the right one.
   */
  @Test
  void scriptOnlyIdsIsRepairedWhenBriefRequiresServiceCall() {
    java.util.List<String> notes = new java.util.ArrayList<>();
    DesignInputCapability capability =
        new DesignInputCapability(
            new IdsDocumentParser(),
            new NormalizedDesignFlowValidator(),
            new MinimalIdsRenderer(),
            new BriefFlowExtractor(),
            new DesignRequirementBriefCoverageValidator(),
            (brief, repairNote) -> {
              notes.add(repairNote);
              return repairNote == null ? SCRIPT_ONLY_HEALTHPROXY_IDS : VALID_IDS;
            });

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Generate full IDS",
                    "requirementBrief",
                    approvedBriefWithMappings())));

    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass());
    assertEquals(2, notes.size(), "the author must be asked twice");
    assertNull(notes.get(0), "the first attempt carries no repair note");
    assertTrue(
        notes.get(1).contains("missing required outbound service-call"),
        () -> "the repair note must name the IDS coverage gap: " + notes.get(1));
  }

  @Test
  void scriptOnlyIdsAfterRepairWaitsInsteadOfDumpingOverlayException() {
    DesignInputCapability capability = capabilityWithFixedGenerate(SCRIPT_ONLY_HEALTHPROXY_IDS);

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Generate full IDS",
                    "requirementBrief",
                    approvedBriefWithMappings())));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertTrue(
        prepared.message().contains("missing required outbound service-call"),
        prepared.message());
    assertFalse(
        prepared.message().contains("Cannot project data mappings: requirement brief has"),
        prepared.message());
    assertFalse(containsIntentRefDump(prepared.message()), prepared.message());
  }

  @Test
  void authoringPromptRequiresOutboundCallsWhenBriefHasServiceCallFacts() {
    String prompt = DesignInputCapability.authoringPrompt(approvedBriefWithMappings());

    assertTrue(prompt.contains("SERVICE_CALL"), prompt);
    assertTrue(prompt.contains("CIP -> that external participant"), prompt);
    assertFalse(prompt.contains("forbids service calls"), prompt);
  }

  @Test
  void authoringPromptKeepsScriptOnlyGuidanceWhenBriefHasNoServiceCall() {
    RequirementBrief scriptOnly =
        new RequirementBrief(
            "Greetings",
            List.of("HTTP GET /greetings"),
            List.of("No service calls"),
            List.of(),
            List.of(),
            "Return greeting text from a script",
            "draft-1",
            "draft",
            List.of(
                fact(
                    "trigger-1",
                    RequirementFactKind.ENDPOINT,
                    "http-trigger",
                    "HTTP GET /greetings")),
            List.of());

    String prompt = DesignInputCapability.authoringPrompt(scriptOnly);

    assertTrue(prompt.contains("forbids service calls"), prompt);
    assertFalse(prompt.contains("CIP -> that external participant"), prompt);
  }

  @Test
  void unreadableFirstDraftIsRepairedWithTheParserComplaint() {
    java.util.List<String> notes = new java.util.ArrayList<>();
    DesignInputCapability capability =
        new DesignInputCapability(
            new IdsDocumentParser(),
            new NormalizedDesignFlowValidator(),
            new MinimalIdsRenderer(),
            new BriefFlowExtractor(),
            new DesignRequirementBriefCoverageValidator(),
            (brief, repairNote) -> {
              notes.add(repairNote);
              // First attempt: prose only, no diagram — the shape the generator actually produced.
              return repairNote == null ? "### Integration flow for CIP Chain - Orders\n\nprose" : VALID_IDS;
            });

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Generate full IDS",
                    "requirementBrief",
                    deriveBrief())));

    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass());
    assertEquals(2, notes.size(), "the author must be asked twice");
    assertNull(notes.get(0), "the first attempt carries no repair note");
    assertTrue(
        notes.get(1).contains("Mermaid sequenceDiagram"),
        () -> "the repair note must quote the parser: " + notes.get(1));
  }

  private static DesignInputCapability capabilityWithFixedGenerate(String generatedMarkdown) {
    return new DesignInputCapability(
        new IdsDocumentParser(),
        new NormalizedDesignFlowValidator(),
        new MinimalIdsRenderer(),
        new BriefFlowExtractor(),
        new DesignRequirementBriefCoverageValidator(),
        (brief, repairNote) -> generatedMarkdown);
  }

  private static StageExecutionContext context(String stageId, Map<String, Object> attributes) {
    return new StageExecutionContext(
        "run-1",
        "conv-1",
        stageId,
        "exec-1",
        "attempt-1",
        new ProductPipelineProfile(
            1, "fixture", "2", List.of(), List.of(), null, List.of()),
        new RunManifest(
            "run-1",
            null,
            List.of(),
            "product",
            "fixture",
            "2",
            "sha",
            "baseline",
            "bsha",
            List.of(),
            "csha",
            null,
            "24.4",
            List.of(),
            null),
        List.of(),
        attributes);
  }

  private static StageOutcome outcome(
      DesignInputCapability capability, StageExecutionContext context) {
    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();
    CapabilitySignal.Completed completed =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow();
    return completed.outcome();
  }

  private static Set<Kind> kinds(StageOutcome outcome) {
    return outcome.candidates().stream().map(ArtifactCandidate::kind).collect(Collectors.toSet());
  }

  private static DesignEntryRoute routePayload(StageOutcome outcome) {
    return outcome.candidates().stream()
        .filter(c -> c.kind() == Kind.DESIGN_ENTRY_ROUTE)
        .map(c -> (DesignEntryRoute) c.payload())
        .findFirst()
        .orElseThrow();
  }

  private static DesignMode modePayload(StageOutcome outcome) {
    return outcome.candidates().stream()
        .filter(c -> c.kind() == Kind.DESIGN_MODE)
        .map(c -> (DesignMode) c.payload())
        .findFirst()
        .orElseThrow();
  }

  private static IdsDocument idsPayload(StageOutcome outcome) {
    return outcome.candidates().stream()
        .filter(c -> c.kind() == Kind.IDS_DOCUMENT)
        .map(c -> (IdsDocument) c.payload())
        .findFirst()
        .orElseThrow();
  }

  private static NormalizedDesignFlow flowPayload(StageOutcome outcome) {
    return outcome.candidates().stream()
        .filter(c -> c.kind() == Kind.NORMALIZED_DESIGN_FLOW)
        .map(c -> (NormalizedDesignFlow) c.payload())
        .findFirst()
        .orElseThrow();
  }

  private static void assertContinuesWithPassThroughEdges(
      StageOutcome prepared, StageOutcomeClass expectedClass) {
    assertEquals(expectedClass, prepared.outcomeClass(), prepared.message());
    assertFalse(
        prepared.message() != null && prepared.message().contains(PipelineGates.MAPPING_GAP),
        prepared.message());
    assertFalse(
        prepared.message() != null
            && prepared.message().toLowerCase(java.util.Locale.ROOT).contains("missing data mapping"),
        prepared.message());
    List<NormalizedDesignFlow.DataMapping> mappings = flowPayload(prepared).dataMappings();
    assertTrue(mappings.isEmpty(), mappings.toString());
    List<NormalizedDesignFlow.Connection> connections = flowPayload(prepared).connections();
    assertFalse(connections.isEmpty(), connections.toString());
    assertTrue(
        connections.stream().anyMatch(connection -> "step-trigger".equals(connection.fromStepId())),
        connections.toString());
    assertTrue(flowPayload(prepared).transformations().isEmpty());
  }

  private static void assertDirectPassThrough(NormalizedDesignFlow flow) {
    assertTrue(flow.dataMappings().isEmpty(), flow.dataMappings().toString());
    assertTrue(flow.transformations().isEmpty());
    assertFalse(flow.connections().isEmpty(), flow.connections().toString());
    assertTrue(
        flow.connections().stream()
            .anyMatch(connection -> "step-trigger".equals(connection.fromStepId())),
        flow.connections().toString());
  }

  private static RequirementBrief healthProxyBrief(List<RequirementDataMapping> mappings) {
    return new RequirementBrief(
        "HealthProxy",
        List.of("HTTP GET /health-proxy"),
        List.of(),
        List.of(),
        List.of(),
        "GET /health-proxy calls Petstore Ext getInventory and returns inventory JSON from a script",
        "draft-1",
        "draft",
        List.of(
            httpTrigger(
                "trigger-1",
                "http-trigger",
                "GET /health-proxy",
                "GET",
                "/health-proxy",
                ""),
            serviceCall(
                "call-1",
                "http-service-call",
                "Call catalog service 'Petstore Ext'",
                "Petstore Ext",
                "getInventory"),
            fact(
                "script-1",
                RequirementFactKind.BEHAVIOR,
                "script",
                "Return inventory JSON from a script")),
        mappings);
  }

  private static RequirementBrief briefWithUnboundLeftoverMappings() {
    return new RequirementBrief(
        "Orders",
        List.of("HTTP POST /orders"),
        List.of(),
        List.of(),
        List.of(),
        "Create order",
        "draft-1",
        "draft",
        List.of(
            httpTrigger(
                "trigger-1",
                "http-trigger",
                "HTTP POST /orders createOrder",
                "POST",
                "/orders",
                "createOrder"),
            serviceCall(
                "call-1",
                "http-service-call",
                "Create an order via Orders API",
                "Orders API",
                "create order")),
        unboundLeftoverMappings());
  }

  private static List<RequirementDataMapping> unboundLeftoverMappings() {
    return List.of(
        leftoverHashMapping(
            RequirementDataMapping.Stage.INITIALIZATION,
            "820d45e25846bb71f78bd5c219f72f87399d7c263d789990f551d38b675bc9e3",
            "b96b0eeae09d098d4fd86aaa47ea807df24901586ae64f91542d242845b2271f"),
        leftoverHashMapping(
            RequirementDataMapping.Stage.RESPONSE,
            "b96b0eeae09d098d4fd86aaa47ea807df24901586ae64f91542d242845b2271f",
            "b8598ee044e21b5e58941a3e896a1c10ed1f3e05c4f031bb743ff8efdcc3d791"));
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

  private static boolean containsIntentRefDump(String message) {
    if (message == null) {
      return false;
    }
    return message.contains("intent refs")
        || message.contains("820d45e2")
        || message.contains("b96b0eea")
        || message.contains("b8598ee0");
  }

  private static RequirementBrief approvedBriefWithMappings() {
    return new RequirementBrief(
        "Orders",
        List.of("HTTP POST /orders"),
        List.of(),
        List.of(),
        List.of(),
        "Create order",
        "draft-1",
        "draft",
        List.of(
            httpTrigger(
                "trigger-1",
                "http-trigger",
                "HTTP POST /orders createOrder",
                "POST",
                "/orders",
                "createOrder"),
            serviceCall(
                "call-1",
                "http-service-call",
                "Create an order via Orders API",
                "Orders API",
                "create order")),
        List.of(
            mapping(
                "map-init",
                RequirementDataMapping.Stage.INITIALIZATION,
                "trigger-1",
                "call-1",
                RequirementDataMapping.Mode.PASS_THROUGH,
                List.of("fact-map")),
            mapping(
                "map-resp",
                RequirementDataMapping.Stage.RESPONSE,
                "call-1",
                "trigger-1",
                RequirementDataMapping.Mode.PASS_THROUGH,
                List.of("fact-map-resp"))));
  }

  private static RequirementBrief deriveBrief() {
    return new RequirementBrief(
        "Orders",
        List.of("HTTP POST /orders"),
        List.of(),
        List.of(),
        List.of(),
        "Create order",
        "draft-1",
        "draft",
        List.of(
            httpTrigger(
                "fact-trigger",
                "async-api-trigger",
                "HTTP POST /orders createOrder",
                "POST",
                "/orders",
                "createOrder"),
            serviceCall(
                "fact-step",
                "http-service-call",
                "Create an order via Orders API",
                "Orders API",
                "create order"),
            fact("fact-p", RequirementFactKind.BEHAVIOR, null, "statement fact-p"),
            fact("fact-map", RequirementFactKind.BEHAVIOR, null, "statement fact-map")),
        List.of(
            mapping(
                "map-1",
                RequirementDataMapping.Stage.INITIALIZATION,
                "fact-trigger",
                "fact-step",
                RequirementDataMapping.Mode.PASS_THROUGH,
                List.of("fact-map"))));
  }

  @Test
  void kafkaCapabilityTriggerReachesDeriveWithoutAMappingGap() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    RequirementBrief brief =
        new RequirementBrief(
            "Kafka pet lookup",
            List.of("topic: user/events"),
            List.of(),
            List.of(),
            List.of(),
            "Consume Kafka user events and look up a pet",
            "draft-1",
            "draft",
            List.of(
                kafkaCapabilityTrigger(),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Look up a pet in Petstore Ext",
                    "Petstore Ext",
                    "getPetById")),
            List.of());

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    brief)));

    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass(), prepared.message());
    assertFalse(
        prepared.message() != null && prepared.message().contains(PipelineGates.MAPPING_GAP),
        prepared.message());
    assertEquals("kafka", flowPayload(prepared).trigger().kind());
    assertEquals("user/events", flowPayload(prepared).trigger().endpointOrTopic());
    assertDirectPassThrough(flowPayload(prepared));
  }

  @Test
  void serviceCallWithoutATriggerDoesNotOfferMappingGapActions() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    RequirementBrief brief =
        new RequirementBrief(
            "Pet lookup",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Look up a pet",
            "draft-1",
            "draft",
            List.of(
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Look up a pet in Petstore Ext",
                    "Petstore Ext",
                    "getPetById")),
            List.of());

    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    brief)));

    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass(), prepared.message());
    assertTrue(
        prepared.message() != null && prepared.message().contains("configured trigger entry"),
        prepared.message());
    assertFalse(
        prepared.message() != null && prepared.message().contains(PipelineGates.MAPPING_GAP),
        prepared.message());
  }

  @Test
  void deriveTreatsMissingApprovedTriggerIdentityAsARecoverableValidationFailure() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
    RequirementBrief briefMissingPath =
        new RequirementBrief(
            "Pets",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "List pets",
            "draft-1",
            "draft",
            List.of(
                fact(
                    "trigger-1",
                    RequirementFactKind.ENDPOINT,
                    "async-api-trigger",
                    "async trigger"),
                serviceCall(
                    "call-1",
                    "http-service-call",
                    "Find pets in Petstore Ext",
                    "Petstore Ext",
                    "findPets")),
            List.of(
                mapping(
                    "map-init",
                    RequirementDataMapping.Stage.INITIALIZATION,
                    "trigger-1",
                    "call-1",
                    RequirementDataMapping.Mode.PASS_THROUGH,
                    List.of("fact-map"))));
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "Derive minimal IDS",
                    "requirementBrief",
                    briefMissingPath)));
    // The approved brief owns the missing trigger identity, so recovery must reopen its producer.
    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, prepared.outcomeClass());
    assertTrue(prepared.message().contains("approved requirement brief"), prepared.message());
  }

  private static RequirementFact kafkaCapabilityTrigger() {
    return new RequirementFact(
        "trigger-1",
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY,
        "kafka-trigger-2",
        "Consume user events",
        "",
        "consumeUserEvent",
        "user/events",
        "",
        "");
  }

  private static RequirementFact fact(
      String id, RequirementFactKind kind, String capabilityKey) {
    return fact(id, kind, capabilityKey, "statement " + id);
  }

  private static RequirementFact fact(
      String id, RequirementFactKind kind, String capabilityKey, String text) {
    return new RequirementFact(
        id, RequirementFactPolarity.POSITIVE, kind, capabilityKey, text);
  }

  private static RequirementFact httpTrigger(
      String id, String capabilityKey, String text, String httpMethod, String path, String operation) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.ENDPOINT,
        capabilityKey,
        text,
        "",
        operation,
        "",
        httpMethod,
        path);
  }

  private static RequirementFact serviceCall(
      String id, String capabilityKey, String text, String participant, String operation) {
    return new RequirementFact(
        id,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        capabilityKey,
        text,
        participant,
        operation,
        "",
        "",
        "");
  }

  private static RequirementDataMapping shapelessPassThrough() {
    return new RequirementDataMapping(
        "map-junk",
        null,
        "",
        "",
        RequirementDataMapping.Mode.PASS_THROUGH,
        List.of(),
        List.of());
  }

  private static RequirementDataMapping mapping(
      String id,
      RequirementDataMapping.Stage stage,
      String from,
      String to,
      RequirementDataMapping.Mode mode,
      List<String> sourceFactIds) {
    return new RequirementDataMapping(id, stage, from, to, mode, List.of(), sourceFactIds);
  }
}
