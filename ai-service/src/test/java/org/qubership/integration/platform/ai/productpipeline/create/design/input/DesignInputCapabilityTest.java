package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class DesignInputCapabilityTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

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
      """;

  @AfterEach
  void unbind() {
    ProductCapabilityCaptureContext.unbind();
  }

  @Test
  void sharedCapabilitySelectsEnterRouteAndPrepareDesignByStageId() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome entry = outcome(capability, context("ids-entry", Map.of("userText", VALID_IDS)));
    assertEquals(StageOutcomeClass.SUCCEEDED, entry.outcomeClass());
    assertTrue(entry.candidates().stream().anyMatch(c -> c.kind() == Kind.DESIGN_ENTRY_ROUTE));

    StageOutcome unsupported = outcome(capability, context("other-stage", Map.of()));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, unsupported.outcomeClass());
  }

  @Test
  void idsEntryEmitsStandardRouteWithoutIdsDocument() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome entry =
        outcome(
            capability,
            context("ids-entry", Map.of("userText", "Create an orders HTTP integration")));
    assertEquals(StageOutcomeClass.SUCCEEDED, entry.outcomeClass());
    assertEquals(DesignEntryRoute.STANDARD, routePayload(entry));
    assertTrue(entry.candidates().stream().noneMatch(c -> c.kind() == Kind.IDS_DOCUMENT));
  }

  @Test
  void providedIdsFailsClosed() {
    DesignInputCapability capability = capturingCapability();
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
                Map.of("designEntryRoute", DesignEntryRoute.PROVIDE, "idsDocument", provided)));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, prepared.outcomeClass());
    assertEquals(
        "IDS is an approval view; provide requirements that can produce a semantic revision",
        prepared.message());
    assertTrue(prepared.candidates().isEmpty());
  }

  @Test
  void agentCaptureEmitsSemanticRevisionAndDerivedIdsWithoutParsing() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "userText",
                    "ignored agent prose after the tool call",
                    "requirementBrief",
                    approvedBrief())));
    assertEquals(StageOutcomeClass.CANDIDATE, prepared.outcomeClass());
    assertEquals(Set.of(Kind.CHAIN_SEMANTIC_REVISION, Kind.IDS_DOCUMENT), kinds(prepared));
    ChainSemanticRevision revision = semanticPayload(prepared);
    IdsDocument ids = idsPayload(prepared);
    assertEquals(linearRevision().revisionId(), revision.revisionId());
    assertTrue(ids.markdown().contains("sequenceDiagram"));
    assertTrue(ids.markdown().contains("autonumber"));
    assertEquals(CanonicalPayloadHash.sha256Hex(revision), ids.normalizedFlowHash());
    assertEquals(IdsDocument.Mode.DERIVED, ids.mode());
  }

  @Test
  void editingIdsMarkdownDoesNotChangeStoredSemanticDigest() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "requirementBrief",
                    approvedBrief())));
    ChainSemanticRevision stored = semanticPayload(prepared);
    IdsDocument ids = idsPayload(prepared);
    String digest = CanonicalPayloadHash.sha256Hex(stored);
    IdsDocument edited =
        new IdsDocument(
            ids.schemaVersion(),
            ids.mode(),
            ids.sourceReference(),
            ids.sourceHash(),
            ids.normalizedFlowHash(),
            ids.rendererVersion(),
            ids.markdown() + "\n<!-- edited by a reviewer -->\n");
    assertEquals(digest, CanonicalPayloadHash.sha256Hex(stored));
    assertNotEquals(ids.markdown(), edited.markdown());
    assertEquals(digest, edited.normalizedFlowHash());
    assertFalse(
        prepared.candidates().stream().anyMatch(c -> c.kind() == Kind.NORMALIZED_DESIGN_FLOW));
  }

  @Test
  void missingCaptureWaitsForTheAgent() {
    DesignInputCapability capability =
        new DesignInputCapability(
            (conversationId, prompt) -> Multi.createFrom().item("prose without a tool call"),
            new DefaultChainSemanticIdsRenderer());
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "designEntryRoute",
                    DesignEntryRoute.STANDARD,
                    "requirementBrief",
                    approvedBrief())));
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertTrue(prepared.candidates().isEmpty());
  }

  private static DesignInputCapability capturingCapability() {
    ChainSemanticCaptureTool captureTool = captureTool();
    return new DesignInputCapability(
        (conversationId, prompt) -> {
          captureTool.captureChainSemanticRevision(linearRevision());
          return Multi.createFrom().item("ignored agent text after the tool call");
        },
        new DefaultChainSemanticIdsRenderer());
  }

  private static ChainSemanticCaptureTool captureTool() {
    CatalogElementDescriptorLoader descriptors = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(descriptors);
    QipKnowledgePackRepository pack = mock(QipKnowledgePackRepository.class);
    Map<String, String> addons = new LinkedHashMap<>();
    for (String addonId : CONTRACT.requiredAddons()) {
      addons.put(addonId, "sha-" + addonId);
    }
    Map<String, String> files = new LinkedHashMap<>();
    for (String fragment : CONTRACT.requiredKnowledgeFragments()) {
      files.put(fragment, "sha-" + fragment);
    }
    when(pack.loadManifest())
        .thenReturn(
            new QipKnowledgePackManifest(
                new QipKnowledgePackVersion("v1", "v1"),
                "test",
                Instant.parse("2026-01-01T00:00:00Z"),
                files,
                List.of(),
                List.of(),
                List.of(),
                CONTRACT.contractVersion(),
                CONTRACT.sha256(),
                addons));
    return new ChainSemanticCaptureTool(
        new DefaultChainSemanticRevisionValidator(),
        new ClasspathCompilerContractRepository(),
        pack,
        descriptors);
  }

  private static StageExecutionContext context(String stageId, Map<String, Object> attributes) {
    return new StageExecutionContext(
        "run-1",
        "conv-1",
        stageId,
        "exec-1",
        "attempt-1",
        new ProductPipelineProfile(1, "fixture", "2", List.of(), List.of(), null, List.of()),
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

  private static IdsDocument idsPayload(StageOutcome outcome) {
    return outcome.candidates().stream()
        .filter(c -> c.kind() == Kind.IDS_DOCUMENT)
        .map(c -> (IdsDocument) c.payload())
        .findFirst()
        .orElseThrow();
  }

  private static ChainSemanticRevision semanticPayload(StageOutcome outcome) {
    return outcome.candidates().stream()
        .filter(c -> c.kind() == Kind.CHAIN_SEMANTIC_REVISION)
        .map(c -> (ChainSemanticRevision) c.payload())
        .findFirst()
        .orElseThrow();
  }

  private static RequirementBrief approvedBrief() {
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
        List.of(new RequirementServiceCall("call-1", "fact-call", "Orders API", "getOrder")),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision linearRevision() {
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-1",
        "chain-greetings",
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "http-in",
                "trigger-http",
                "op-shared",
                0,
                new SemanticProvenance(List.of("trigger-1")),
                null)),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of("trigger-1"))),
            new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "node-call", "call-1", "getOrder", new SemanticProvenance(List.of("fact-call")))),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "edge-entry",
                "trigger-http",
                "op-shared",
                null,
                new SemanticRoute.Sequence(),
                null),
            new SemanticExecutionEdge(
                "edge-call",
                "op-shared",
                "node-call",
                null,
                new SemanticRoute.Sequence(),
                "map-body")),
        List.of(),
        List.of(
            new MappingIntent(
                "map-body",
                "edge-call",
                MappingPort.OUTPUT,
                "edge-call",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("id", "orderId", null)))),
        List.of(),
        List.of(),
        List.of());
  }
}
