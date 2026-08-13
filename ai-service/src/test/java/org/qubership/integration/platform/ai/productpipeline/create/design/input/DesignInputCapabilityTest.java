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
  void missingMappingIntentReturnsWaitingForInput() {
    DesignInputCapability capability = capabilityWithFixedGenerate("unused");
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
                fact("trigger-1", RequirementFactKind.ENDPOINT, "http-trigger"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "http-service-call")),
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
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertEquals(
        PipelineGates.MAPPING_GAP,
        PipelineGates.gateOf(prepared.message()).orElseThrow(),
        prepared.message());
    assertFalse(prepared.message().contains("Reply PASS_THROUGH"), prepared.message());
    DesignInputIdsPathPrompts.MappingGapView view =
        DesignInputIdsPathPrompts.parseMappingGapWait(prepared.message());
    assertFalse(view.question().isBlank());
    assertFalse(view.missingEdges().isEmpty());
    assertTrue(
        view.missingEdges().stream().anyMatch(edge -> edge.contains("INITIALIZATION")),
        view.missingEdges().toString());
    assertTrue(
        view.missingEdges().stream().anyMatch(edge -> edge.contains("ENDPOINT")),
        view.missingEdges().toString());
    assertTrue(
        view.missingEdges().stream()
            .noneMatch(edge -> edge.contains("mapping required:")),
        "readable edges must not use the technical id format: " + view.missingEdges());
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
                fact("trigger-1", RequirementFactKind.ENDPOINT, "http-trigger"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "http-service-call")),
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
                fact("trigger-1", RequirementFactKind.ENDPOINT, "http-trigger"),
                fact("call-1", RequirementFactKind.SERVICE_CALL, "http-service-call")),
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
          public String askIdsPathChoice(String reference) {
            return "LLM IDS path choice in conversation language";
          }

          @Override
          public String askMappingGap(String reference, String missingEdges, String pendingMode) {
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

  /**
   * Declining the document changes who sees it, not who writes it.
   *
   * <p>Both answers author through the generator. DERIVE differs only in outcome: it advances the
   * run instead of producing an approval candidate, so nothing is printed and nothing is approved.
   */
  @Test
  void deriveAuthorsThroughTheGeneratorAndAdvances() {
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
                    deriveBrief())));

    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(DesignMode.DERIVE, modePayload(prepared));
    assertEquals(IdsDocument.Mode.DERIVED, idsPayload(prepared).mode());
    assertEquals("cip-design-generator@1", idsPayload(prepared).rendererVersion());
  }

  /**
   * A document the parser cannot read is repaired, not surfaced to the caller.
   *
   * <p>The heading and the sequence diagram are asked for in prose, so the author misses them from
   * time to time. Nobody downstream can supply what the author left out, which makes a question
   * the wrong move and a rewrite the right one.
   */
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
            fact("trigger-1", RequirementFactKind.ENDPOINT, "http-trigger"),
            fact("call-1", RequirementFactKind.SERVICE_CALL, "http-service-call")),
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
            fact(
                "fact-trigger",
                RequirementFactKind.ENDPOINT,
                "async-api-trigger",
                "HTTP POST /orders createOrder"),
            fact(
                "fact-step",
                RequirementFactKind.SERVICE_CALL,
                "http-service-call",
                "Orders API: create order"),
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
  void unusableAuthoredDesignAsksInsteadOfFailingTheStage() {
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
                fact(
                    "call-1",
                    RequirementFactKind.SERVICE_CALL,
                    "http-service-call",
                    "Petstore Ext: findPets")),
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
    // The authored document cannot be turned into a valid flow. Asking beats failing: a
    // VALIDATION_FAILURE reopens the previous approval and drops the caller back onto the
    // requirement brief, losing the turn over something one more fact would settle.
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertFalse(prepared.message() == null || prepared.message().isBlank(), "must say what is missing");
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
