package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineCompatibilityAnalyzer;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBinding;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DefaultChainSemanticIdsRenderer;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignEntryRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

class ProductPipelineProfileCatalogCutoverTest {

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

  @Test
  void catalogContainsCreateChainV1AndV2() throws Exception {
    ProductPipelineProfile v1 = parseClasspath("create-chain-v1.yaml");
    ProductPipelineProfile v2 = parseClasspath("create-chain-v2.yaml");
    ProductPipelineProfileCatalog catalog = new ProductPipelineProfileCatalog(List.of(v1, v2));
    assertEquals(v1, catalog.require("create-chain", "1"));
    assertEquals(v2, catalog.require("create-chain", "2"));
    assertTrue(
        getClass().getResource("/product-pipelines/profiles/create-plan-v1.yaml") == null,
        "create-plan-v1.yaml must be absent from classpath");
  }

  @Test
  void createChainV2ProfileMatchesCutoverContract() throws Exception {
    ProductPipelineProfile v2 = parseClasspath("create-chain-v2.yaml");
    assertEquals("2", v2.profileVersion());
    assertEquals(
        List.of(
            "ids-document",
            "chain-semantic-revision",
            "design-plan-report",
            "design-execution-plan",
            "implementation-plan"),
        stage(v2, "design-planning").approval().candidateSet().stream()
            .map(ArtifactTypeRef::type)
            .toList());
    List<String> executionConsumes =
        stage(v2, "design-execution").consumes().stream().map(ArtifactTypeRef::type).toList();
    assertTrue(executionConsumes.contains("chain-semantic-revision"));
    assertFalse(executionConsumes.contains("normalized-design-flow"));
    assertTrue(v2.stages().stream().allMatch(stage -> stage.retry() != null));
    assertEquals(List.of(2), v2.compilerPipeline().supportedIndexSchemas());
    assertEquals(
        List.of(new ArtifactTypeRef("requirement-brief", 1)),
        v2.compilerPipeline().preSatisfiedArtifacts());
    assertEquals(
        List.of(
            new ArtifactTypeRef("requirement-brief", 1), new ArtifactTypeRef("ids-document", 1)),
        stage(v2, "design-input").optionalConsumes());
    assertEquals(
        List.of(new ArtifactTypeRef("catalog-binding-hint", 1)),
        stage(v2, "design-execution").optionalConsumes());
    assertEquals(
        List.of(new ArtifactTypeRef("catalog-binding-hint", 1)),
        stage(v2, "requirement-discovery").optionalProduces());
    assertEquals(
        List.of(
            new ArtifactTypeRef("chain-semantic-revision", 1),
            new ArtifactTypeRef("ids-document", 1)),
        stage(v2, "design-input").approval().candidateSet());
    assertEquals(
        new ArtifactTypeRef("chain-semantic-revision", 1),
        stage(v2, "design-input").approval().artifact());
    assertEquals(
        "CATALOG_FIRST_V1", stage(v2, "design-planning").approval().bindingResolutionPolicy());
    assertNull(stage(v2, "requirement-discovery").approval());
    assertNotNull(stage(v2, "requirement-analysis").approval());
    assertTrue(
        v2.stages().stream()
            .noneMatch(s -> "ids-bypass".equals(s.stageId()) || "ids-skip".equals(s.stageId())));
    assertTrue(
        v2.stages().stream().anyMatch(s -> "import-stage".equals(s.stageId())),
        "create-chain@2 must keep import-stage (ADR 0001)");
  }

  @Test
  void threeProfileCopiesAreByteIdentical() throws Exception {
    byte[] main =
        Files.readAllBytes(
            Path.of("src/main/resources/product-pipelines/profiles/create-chain-v2.yaml"));
    byte[] test =
        Files.readAllBytes(
            Path.of("src/test/resources/product-pipelines/profiles/create-chain-v2.yaml"));
    byte[] skills =
        Files.readAllBytes(
            Path.of("../integration-platform-skills/product-pipelines/profiles/create-chain-v2.yaml"));
    assertEquals(new String(main), new String(test));
    assertEquals(new String(main), new String(skills));
  }

  @Test
  void newRunsSelectV2WhilePersistedV1BindingsRemainReadable() throws Exception {
    ProductPipelineProfile v1 = parseClasspath("create-chain-v1.yaml");
    ProductPipelineProfile v2 = parseClasspath("create-chain-v2.yaml");
    ProductPipelineProfileCatalog catalog = new ProductPipelineProfileCatalog(List.of(v1, v2));
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    ObjectMapper mapper = new ObjectMapper();
    CreateRunBindingStore bindingStore = new CreateRunBindingStore(blobs, mapper);
    FakeKnowledgeClient knowledge = FakeKnowledgeClient.defaultFixture();
    CompilerRunPinResolver pinResolver = stubPinResolver();
    Clock clock = Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);

    CreateRunBinding historicalV1 =
        new CreateRunBinding(
            "conv-v1-historical",
            "conv-v1-historical-create-chain-1",
            new org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest(
                "conv-v1-historical-create-chain-1",
                null,
                List.of(),
                "product",
                "create-chain",
                "1",
                "create-chain@1",
                "reference-baseline-v1",
                "reference-baseline-v1",
                List.of(),
                "closure",
                knowledge.context().packageRef(),
                "2026.1",
                List.of(),
                null),
            Instant.parse("2026-07-22T12:00:00Z"));
    bindingStore.create(historicalV1);

    CreateRunSelectionService selectionService =
        new CreateRunSelectionService(
            "2026.1", knowledge, bindingStore, catalog, pinResolver, clock);

    assertEquals("2", selectionService.selectOrCreate("conv-new-v2").runManifest().profileVersion());
    assertEquals(
        "1",
        bindingStore.load("conv-v1-historical").orElseThrow().runManifest().profileVersion());
    assertEquals(
        "1", selectionService.selectOrCreate("conv-v1-historical").runManifest().profileVersion());
  }

  @Test
  void compatibilityAnalyzerDefaultsIncludeCreateChainV1AndV2() {
    assertEquals(
        List.of(
            CompilerPipelineCompatibilityAnalyzer.PROFILE_CREATE_CHAIN_V1,
            CompilerPipelineCompatibilityAnalyzer.PROFILE_CREATE_CHAIN_V2),
        List.of(
            CompilerPipelineCompatibilityAnalyzer.PROFILE_CREATE_CHAIN_V1,
            CompilerPipelineCompatibilityAnalyzer.PROFILE_CREATE_CHAIN_V2));
  }

  @Test
  void fixedApprovalPolicyWaitsForSemanticDesignInput() {
    var revision =
        SemanticFixtures.revision(
            List.of(SemanticFixtures.entry("http-in", "trigger-http")));
    DesignInputCapability designInput =
        new DesignInputCapability(
            (conversationId, prompt) -> {
              ProductCapabilityCaptureContext.offerSemantic(revision);
              return Multi.createFrom().empty();
            },
            new DefaultChainSemanticIdsRenderer());
    DesignPlanningCapability designPlanningCapability = mock(DesignPlanningCapability.class);
    when(designPlanningCapability.capabilityId()).thenReturn(DesignPlanningCapability.CAPABILITY_ID);
    when(designPlanningCapability.execute(any()))
        .thenReturn(
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.SUCCEEDED, "planned"))));

    StageOutcome captured =
        outcome(
            designInput,
            designInputContext(
                DesignEntryRoute.STANDARD,
                "Generate full IDS",
                approvedBriefWithMappings(),
                null));
    StageOutcome provide =
        outcome(
            designInput,
            designInputContext(DesignEntryRoute.PROVIDE, null, null, providedIdsDocument()));

    List<String> waitingForApprovalStages = waitingStages(captured, "design-input");
    List<String> waitingForApprovalStagesProvide = waitingStages(provide, "design-input");

    assertTrue(waitingForApprovalStages.contains("design-input"));
    assertTrue(waitingForApprovalStagesProvide.stream().noneMatch("design-input"::equals));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, provide.outcomeClass());
  }

  @Test
  void createPlanProfileResourceIsAbsent() {
    assertTrue(
        getClass().getResourceAsStream("/product-pipelines/profiles/create-plan-v1.yaml") == null);
  }

  private static List<String> waitingStages(StageOutcome outcome, String stageId) {
    List<String> stages = new ArrayList<>();
    if (outcome.outcomeClass() == StageOutcomeClass.CANDIDATE) {
      stages.add(stageId);
    }
    return stages;
  }

  private static StageOutcome outcome(StageCapability capability, StageExecutionContext context) {
    return capability.execute(context).collect().asList().await().indefinitely().stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .map(CapabilitySignal.Completed::outcome)
        .findFirst()
        .orElseThrow(() -> new AssertionError("capability emitted no Completed signal"));
  }

  private static StageExecutionContext designInputContext(
      DesignEntryRoute route,
      String userText,
      RequirementBrief brief,
      org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument ids) {
    java.util.HashMap<String, Object> attributes = new java.util.HashMap<>();
    attributes.put("designEntryRoute", route);
    if (userText != null) {
      attributes.put("userText", userText);
    }
    if (brief != null) {
      attributes.put("requirementBrief", brief);
    }
    if (ids != null) {
      attributes.put("idsDocument", ids);
    }
    return new StageExecutionContext(
        "run-design-input",
        "conv-design-input",
        "design-input",
        "exec-1",
        "attempt-1",
        new ProductPipelineProfile(1, "create-chain", "2", List.of(), List.of(), null, List.of()),
        null,
        List.of(),
        Map.copyOf(attributes));
  }

  private static StageExecutionContext planningContext(String runId, DesignEntryRoute route) {
    return new StageExecutionContext(
        runId,
        "conv-" + runId,
        "design-planning",
        "exec-1",
        "attempt-1",
        new ProductPipelineProfile(1, "create-chain", "2", List.of(), List.of(), null, List.of()),
        null,
        List.of(),
        Map.of("designEntryRoute", route));
  }

  private static org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument
      providedIdsDocument() {
    return new org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument(
        "1",
        org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument.Mode
            .PROVIDED,
        "user-ids",
        "hash",
        "pending-normalization",
        "ids-entry@1",
        VALID_IDS);
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
            fact("fact-trigger", RequirementFactKind.ENDPOINT, "async-api-trigger"),
            fact("fact-step", RequirementFactKind.SERVICE_CALL, "http-service-call"),
            fact("fact-p", RequirementFactKind.BEHAVIOR, null),
            fact("fact-map", RequirementFactKind.BEHAVIOR, null)),
        List.of(
            mapping(
                "map-init",
                RequirementDataMapping.Stage.INITIALIZATION,
                "fact-trigger",
                "fact-step",
                RequirementDataMapping.Mode.PASS_THROUGH,
                List.of("fact-map"))));
  }

  private static RequirementFact fact(
      String id, RequirementFactKind kind, String capabilityKey) {
    return new RequirementFact(
        id, RequirementFactPolarity.POSITIVE, kind, capabilityKey, "statement " + id);
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

  private static ProfileStage stage(ProductPipelineProfile profile, String stageId) {
    return profile.stages().stream()
        .filter(s -> stageId.equals(s.stageId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing stage " + stageId));
  }

  private static ProductPipelineProfile parseClasspath(String fileName) throws Exception {
    try (InputStream in =
        ProductPipelineProfileCatalogCutoverTest.class.getResourceAsStream(
            "/product-pipelines/profiles/" + fileName)) {
      return ProductPipelineProfileParser.parse(in);
    }
  }

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "pkg",
            "1",
            "digest",
            1,
            "idx-1",
            "idx-digest",
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            List.of("planning"),
            Map.of(),
            Map.of("skill", "a".repeat(64)),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    CompilerRunPinResolver resolver = mock(CompilerRunPinResolver.class);
    when(resolver.resolve(any(), any())).thenReturn(pin);
    return resolver;
  }
}
