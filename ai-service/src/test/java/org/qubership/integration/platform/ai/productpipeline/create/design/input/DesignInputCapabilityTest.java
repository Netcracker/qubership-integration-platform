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
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
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
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, entry.outcomeClass());
    assertEquals(DesignInputCapability.PROVIDED_IDS_REJECTED, entry.message());

    StageOutcome unsupported = outcome(capability, context("other-stage", Map.of()));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, unsupported.outcomeClass());
  }

  @Test
  void idsEntrySucceedsWithoutIdsDocument() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome entry =
        outcome(
            capability,
            context("ids-entry", Map.of("userText", "Create an orders HTTP integration")));
    assertEquals(StageOutcomeClass.SUCCEEDED, entry.outcomeClass());
    assertTrue(entry.candidates().stream().noneMatch(c -> c.kind() == Kind.IDS_DOCUMENT));
  }

  @Test
  void providedIdsFailsClosed() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome entry = outcome(capability, context("ids-entry", Map.of("userText", VALID_IDS)));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, entry.outcomeClass());
    assertEquals(DesignInputCapability.PROVIDED_IDS_REJECTED, entry.message());
    assertTrue(entry.candidates().isEmpty());
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
                    "userText",
                    "ignored agent prose after the tool call",
                    "requirementBrief",
                    ChainSemanticCaptureFixtures.approvedBrief())));
    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(Set.of(Kind.CHAIN_SEMANTIC_REVISION, Kind.IDS_DOCUMENT), kinds(prepared));
    ChainSemanticRevision revision = semanticPayload(prepared);
    IdsDocument ids = idsPayload(prepared);
    assertTrue(revision.revisionId().startsWith("semantic-"), revision.revisionId());
    assertTrue(ids.markdown().contains("sequenceDiagram"));
    assertTrue(ids.markdown().contains("autonumber"));
    assertEquals(CanonicalPayloadHash.sha256Hex(revision), ids.normalizedFlowHash());
    assertEquals(IdsDocument.Mode.DERIVED, ids.mode());
  }

  @Test
  void uncoveredRockyBriefWaitsForMappingGapWithoutCapture() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome prepared =
        outcome(
            capability,
            context("design-input", Map.of("requirementBrief", ChainSemanticCaptureFixtures.rockyBrief())));
    assertEquals(StageOutcomeClass.NEEDS_INPUT, prepared.outcomeClass());
    assertTrue(prepared.candidates().isEmpty());
    assertEquals(PipelineGates.MAPPING_GAP, PipelineGates.gateOf(prepared.message()).orElse(""));
    MappingGapWait.View view = MappingGapWait.parse(PipelineGates.strip(prepared.message()));
    assertTrue(view.missingEdges().contains("task-start -> create-task"));
    assertTrue(view.missingEdges().contains("create-task -> task-result"));
  }

  @Test
  void matchingPassThroughConfirmationSkipsTheGate() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.rockyBrief();
    MappingGapPassThroughConfirmation confirmation =
        new MappingGapPassThroughConfirmation(
            "sha-rocky",
            MappingGapCoverage.uncovered(brief).stream()
                .map(t -> new MappingGapPassThroughConfirmation.TransitionRef(
                    t.sourceInteractionId(), t.targetInteractionId()))
                .toList());
    StageOutcome prepared =
        outcome(
            capturingCapability(),
            context(
                "design-input",
                Map.of(
                    "requirementBrief", brief,
                    "mappingGapPassThrough", confirmation,
                    "requirementBriefContentHash", "sha-rocky")));
    assertEquals(StageOutcomeClass.SUCCEEDED, prepared.outcomeClass());
    assertEquals(
        Set.of(Kind.CHAIN_SEMANTIC_REVISION, Kind.IDS_DOCUMENT), kinds(prepared));
  }

  @Test
  void authoringPromptCopiesPluralSourceFactIds() {
    String prompt =
        DesignInputCapability.authoringPrompt(
            ChainSemanticCaptureFixtures.approvedBrief(), CONTRACT);
    assertTrue(prompt.contains("sourceFactIds"), prompt);
    assertTrue(prompt.contains("omit it on every edge"), prompt);
    assertTrue(prompt.contains("External interaction anchors are server-owned"), prompt);
    assertTrue(prompt.contains("nodeId=http-in"), prompt);
    assertTrue(prompt.contains("nodeId=call-1"), prompt);
  }

  @Test
  void authoringPromptOmitsTriggerBackedServiceCalls() {
    String prompt =
        DesignInputCapability.authoringPrompt(
            ChainSemanticCaptureFixtures.catalogBoundAsyncApiTriggerBrief(), CONTRACT);
    assertTrue(prompt.contains("nodeId=async-in"), prompt);
    assertFalse(prompt.contains("nodeId=consume-om"), prompt);
  }

  @Test
  void authoringPromptListsApprovedBusinessTransitions() {
    String prompt =
        DesignInputCapability.authoringPrompt(
            ChainSemanticCaptureFixtures.rockyBrief(), CONTRACT);
    assertTrue(prompt.contains("nodeId=task-start"), prompt);
    assertTrue(prompt.contains("nodeId=create-task"), prompt);
    assertTrue(prompt.contains("nodeId=task-result"), prompt);
    assertTrue(prompt.contains("task-start -> create-task"), prompt);
    assertTrue(prompt.contains("create-task -> task-result"), prompt);
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
                    "requirementBrief",
                    ChainSemanticCaptureFixtures.approvedBrief())));
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
  }

  @Test
  void secondProvidedIdsAttemptDoesNotReplaceCapturedRevision() {
    DesignInputCapability capability = capturingCapability();
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "requirementBrief",
                    ChainSemanticCaptureFixtures.approvedBrief())));
    ChainSemanticRevision stored = semanticPayload(prepared);
    String digest = CanonicalPayloadHash.sha256Hex(stored);
    IdsDocument edited =
        new IdsDocument(
            "1",
            IdsDocument.Mode.PROVIDED,
            "user-ids",
            "edited-hash",
            digest,
            "reviewer@1",
            idsPayload(prepared).markdown() + "\n<!-- edited by a reviewer -->\n");
    StageOutcome second =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "idsDocument",
                    edited,
                    "requirementBrief",
                    ChainSemanticCaptureFixtures.approvedBrief())));
    assertEquals(StageOutcomeClass.SUCCEEDED, second.outcomeClass());
    ChainSemanticRevision secondStored = semanticPayload(second);
    assertEquals(stored.revisionId(), secondStored.revisionId());
    assertEquals(digest, CanonicalPayloadHash.sha256Hex(secondStored));
    assertNotEquals(edited.markdown(), idsPayload(second).markdown());
  }

  @Test
  void missingCaptureIsAContractFailureThePipelineCanRepair() {
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
                    "requirementBrief",
                    ChainSemanticCaptureFixtures.approvedBrief())));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, prepared.outcomeClass());
    assertEquals("prose without a tool call", prepared.message());
    assertEquals(
        RecoveryCauseCode.CONTRACT_SHAPE, prepared.recoveryCause().causeCode());
    assertTrue(prepared.candidates().isEmpty());
  }

  @Test
  void rejectedCaptureKeepsTheAgentExplanation() {
    String explanation =
        "The semantic revision could not be captured because the onTaskResult trigger must have"
            + " exactly one outgoing edge.";
    DesignInputCapability capability =
        new DesignInputCapability(
            (conversationId, prompt) -> Multi.createFrom().item(explanation),
            new DefaultChainSemanticIdsRenderer());
    StageOutcome prepared =
        outcome(
            capability,
            context(
                "design-input",
                Map.of(
                    "requirementBrief",
                    ChainSemanticCaptureFixtures.approvedBrief())));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, prepared.outcomeClass());
    assertEquals(explanation, prepared.message());
    assertEquals(explanation, prepared.recoveryCause().findings().getFirst().message());
  }

  @Test
  void repairTurnPromptIncludesTheCaptureRejectionAndAuthorCorrection() {
    java.util.concurrent.atomic.AtomicReference<String> seenPrompt =
        new java.util.concurrent.atomic.AtomicReference<>();
    String rejection =
        "Trigger node 'trigger-om-onTaskResult' must have exactly one outgoing edge";
    DesignInputCapability capability =
        new DesignInputCapability(
            (conversationId, prompt) -> {
              seenPrompt.set(prompt);
              return Multi.createFrom().item("prose without a tool call");
            },
            new DefaultChainSemanticIdsRenderer());
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("requirementBrief", ChainSemanticCaptureFixtures.approvedBrief());
    attributes.put("userText", "OM to Salesforce WFM original request");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, rejection);
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, "CONTRACT_SHAPE: " + rejection);
    attributes.put(
        ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR,
        "Treat onTaskResult as a Kafka produce, not a trigger");
    outcome(capability, context("design-input", attributes));
    String prompt = seenPrompt.get();
    assertTrue(prompt.contains(rejection));
    assertTrue(prompt.contains("Treat onTaskResult as a Kafka produce, not a trigger"));
    assertTrue(prompt.contains("Rebuild the topology so this rejection cannot recur"));
    assertFalse(prompt.contains("OM to Salesforce WFM original request"));
  }

  @Test
  void designAgentPromptEscapesQuteBracesFromTheBrief() {
    java.util.concurrent.atomic.AtomicReference<String> seenPrompt =
        new java.util.concurrent.atomic.AtomicReference<>();
    DesignInputCapability capability =
        new DesignInputCapability(
            (conversationId, prompt) -> {
              seenPrompt.set(prompt);
              return Multi.createFrom().item("prose without a tool call");
            },
            new DefaultChainSemanticIdsRenderer());
    outcome(
        capability,
        context(
            "design-input",
            Map.of("requirementBrief", ChainSemanticCaptureFixtures.approvedBrief())));
    String prompt = seenPrompt.get();
    assertTrue(prompt.contains("path=/orders/\\{id}"), prompt);
    assertFalse(prompt.contains("path=/orders/{id}"), prompt);
  }

  private static DesignInputCapability capturingCapability() {
    ChainSemanticCaptureTool captureTool = captureTool();
    return new DesignInputCapability(
        (conversationId, prompt) -> {
          ChainSemanticCapture capture =
              prompt != null && prompt.contains("nodeId=task-start")
                  ? ChainSemanticCaptureFixtures.rockyCapture()
                  : ChainSemanticCaptureFixtures.linearCapture();
          captureTool.captureChainSemanticRevision(capture);
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
    files.put("knowledge/ai/validation-rules.yaml", "sha-validation-rules");
    files.put("knowledge/ai/GENERATOR_CONTRACTS.md", "sha-generator-contracts");
    files.put("knowledge/ai/generator-rule-mapping.md", "sha-generator-rule-mapping");
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
        new ChainSemanticCaptureAdapter(new ChainSemanticCanonicalizer()),
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
}
