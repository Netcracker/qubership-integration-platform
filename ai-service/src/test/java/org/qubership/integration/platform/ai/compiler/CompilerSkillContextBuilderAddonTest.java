package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonBuildSupport;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemCipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;

class CompilerSkillContextBuilderAddonTest {

  private CompilerSkillContextBuilder contextBuilder;
  private CompilerSkillDocumentService documentService;
  private CompilerSkillAddonRepository addonRepository;
  private String previousAddonPackRoot;

  @BeforeEach
  void setUp(@TempDir Path outputDir, @TempDir Path addonRoot) throws Exception {
    previousAddonPackRoot = System.getProperty("qip.ai.qipknowledge.addon-pack-root");
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    Path fixtureAddonRoot = CipKnowledgePackFixturePaths.addonRoot();
    Files.createDirectories(addonRoot.resolve("global"));
    Files.copy(
        fixtureAddonRoot.resolve("global/graph-patch-contract.md"),
        addonRoot.resolve("global/graph-patch-contract.md"));
    Files.copy(
        fixtureAddonRoot.resolve("global/runtime-contract.md"),
        addonRoot.resolve("global/runtime-contract.md"));
    Files.createDirectories(addonRoot.resolve("skills"));
    try (java.nio.file.DirectoryStream<Path> addons =
        Files.newDirectoryStream(fixtureAddonRoot.resolve("skills"), "*.addon.md")) {
      for (Path addon : addons) {
        Files.copy(addon, addonRoot.resolve("skills").resolve(addon.getFileName()));
      }
    }

    System.setProperty("qip.ai.qipknowledge.addon-pack-root", addonRoot.toString());
    CipKnowledgePackBuildGenerator.generate(CipKnowledgePackFixturePaths.packRoot(), outputDir);
    CompilerSkillAddonBuildSupport.materialize(
        addonRoot, outputDir.resolve(CipKnowledgePackFixturePaths.PACK_DIR));

    CipKnowledgePackVersion version = CipKnowledgePackFixturePaths.packVersion();
    FilesystemCipKnowledgePackRepository repository =
        new FilesystemCipKnowledgePackRepository(outputDir, version);
    addonRepository = org.mockito.Mockito.spy(
        CompilerSkillAddonRepository.forFilesystem(outputDir, version, getClass().getClassLoader()));
    documentService = new CompilerSkillDocumentService(repository);
    CompilerSkillRuntimeEligibility runtimeEligibility =
        new CompilerSkillRuntimeEligibility(repository);
    contextBuilder =
        new CompilerSkillContextBuilder(
            new ObjectMapper(),
            repository,
            addonRepository,
            runtimeEligibility,
            testKnowledgeClient(), testKnowledgeClient());
  }

  @AfterEach
  void restoreAddonPackRoot() {
    if (previousAddonPackRoot == null) {
      System.clearProperty("qip.ai.qipknowledge.addon-pack-root");
    } else {
      System.setProperty("qip.ai.qipknowledge.addon-pack-root", previousAddonPackRoot);
    }
  }

  private static FakeKnowledgeClient testKnowledgeClient() {
    return FakeKnowledgeClient.defaultFixture();
  }

  private static void copyAddon(Path fixtureAddonRoot, Path addonRoot, String fileName)
      throws Exception {
    Files.copy(fixtureAddonRoot.resolve("skills/" + fileName), addonRoot.resolve("skills/" + fileName));
  }

  @Test
  void structureGeneratorAddonExposesCaptureChainStructure(@TempDir Path outputDir)
      throws Exception {
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    CipKnowledgePackBuildGenerator.generate(
        CipKnowledgePackFixturePaths.packRoot(),
        outputDir,
        CipKnowledgePackFixturePaths.addonRoot());
    CipKnowledgePackVersion version = CipKnowledgePackFixturePaths.packVersion();
    CompilerSkillAddonRepository addonRepository =
        CompilerSkillAddonRepository.forFilesystem(outputDir, version, getClass().getClassLoader());

    assertEquals(
        CaptureTool.CAPTURE_CHAIN_STRUCTURE,
        addonRepository.loadRuntimeMetadata("cip-structure-generator").orElseThrow().captureTool());
  }

  @Test
  void errorHandlingGeneratorLoadsFromFixturePack() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-error-handling-generator");
    assertEquals("cip-error-handling-generator", document.capabilityId());
    assertTrue(document.supported());
  }

  @Test
  void addonPackRootSystemPropertyIsRestoredAfterEachTest() {
    // After @AfterEach from the previous method, property must match the captured previous value
    // (null → cleared, otherwise restored). This method also exercises restore on exit.
    String during = System.getProperty("qip.ai.qipknowledge.addon-pack-root");
    assertTrue(during != null && !during.isBlank());
  }

  @Test
  void appendsAddonsBeforeUpstreamSkillDocument() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-security-generator");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Secure endpoint", "RBAC required", null, null, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    int addonIndex = message.indexOf("Compiler skill addon (skills/cip-security-generator.addon.md):");
    int skillDocIndex =
        message.indexOf("Compiler skill document (skills/cip-security-generator/SKILL.md):");
    assertTrue(addonIndex >= 0);
    assertTrue(skillDocIndex > addonIndex);
    assertTrue(message.contains("Follow the compiler skill addon for this-turn capture steps"));
    assertTrue(message.contains("Upstream SKILL.md below owns domain behavior"));
    assertTrue(message.contains("ai-service runtime addon (global/graph-patch-contract.md):"));
    assertTrue(message.contains("accessControlType"));
    assertTrue(message.contains("RBAC"));
    assertTrue(message.contains("Runtime Context Package"));
    assertFalse(message.contains("Supporting knowledge excerpts:"));
    assertFalse(message.contains("## Knowledge Map"));

    String addonBlock = extractSkillAddonBlock(message, "skills/cip-security-generator.addon.md");
    assertFalse(addonBlock.contains("## Upstream"));
    assertFalse(addonBlock.contains("## Runtime metadata"));
    assertFalse(addonBlock.contains("## Readiness signals"));
    assertFalse(addonBlock.contains("## Examples"));
    assertFalse(addonBlock.contains("## Runtime contract"));
    assertTrue(addonBlock.contains("## Plan property encoding"));
  }

  @Test
  void runtimeContextPackageIsIncludedWhenPresent() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-security-generator");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Secure endpoint", "RBAC required", null, null, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("Runtime Context Package"));
    assertFalse(message.contains("## Knowledge Map"));
  }

  @Test
  void errorHandlingGeneratorIncludesAddonWhenPresentInFixture() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-error-handling-generator");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Greetings", "Static response", null, null, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("GEN-04") || message.contains("error-handling") || message.contains("try-catch"));
    assertTrue(message.contains("Compiler skill addon (skills/cip-error-handling-generator.addon.md):"));
  }

  @Test
  void errorHandlingGeneratorRestatesConfigurationScopeAfterSkillDocument() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-error-handling-generator");
    String editConstraint =
        "The structure stage owns new nodes, containment, and edge rewrites. A configuration"
            + " generator may change only the node ids in its Active generator plan slice.";
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot(
            "Wrap the script in try-catch",
            "Add error handling",
            null,
            null,
            null,
            editConstraint);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    int skillDocIndex = message.indexOf("Compiler skill document");
    int finalConstraintIndex = message.indexOf("Final edit constraint");
    assertTrue(skillDocIndex >= 0);
    assertTrue(finalConstraintIndex >= 0);
    assertTrue(skillDocIndex < finalConstraintIndex);
    String finalConstraint = message.substring(finalConstraintIndex);
    assertTrue(finalConstraint.contains("The structure stage owns new nodes"));
    assertTrue(finalConstraint.contains("Active generator plan slice"));
    assertFalse(finalConstraint.contains("When wrapping"));
  }

  @Test
  void editStructurePromptIncludesTheImportedGraphAndStructuralBoundary() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-structure-generator");
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            java.util.List.of(
                new ChainPlanNode("script", "script", "Script", null, null, java.util.List.of())),
            java.util.List.of());
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot(
            "Wrap the script",
            "",
            null,
            graph,
            null,
            "The listed intent targets are the approved structural boundary: script.");

    String message =
        contextBuilder.buildUserMessage(
            null, document, snapshot, null, CaptureTool.CAPTURE_CHAIN_STRUCTURE);

    assertTrue(message.contains("Current ChainPlanGraph JSON:"));
    assertTrue(message.contains("\"nodeId\""));
    assertTrue(message.contains("\"script\""));
    assertTrue(message.contains("approved structural boundary: script"));
    int skillDocIndex = message.indexOf("Compiler skill document");
    int finalConstraintIndex = message.indexOf("Final edit constraint");
    assertTrue(skillDocIndex >= 0, message);
    assertTrue(finalConstraintIndex >= 0, message);
    assertTrue(skillDocIndex < finalConstraintIndex, message);
    assertTrue(
        message.substring(finalConstraintIndex).contains("approved structural boundary: script"));
  }

  @Test
  void patternSelectorPromptAsksForGoldenPatternCapture() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-pattern-selector");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot(
            "HTTP API", "goal: HTTP API\nsummary: backend calls", null, null, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("captureSelectedPattern"));
    assertTrue(message.contains("Do not call captureChainPlan or captureGraphPatch"));
  }

  @Test
  void graphConstructionIncludesSelectedPatternFromWorkspace() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-chain-generator");
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("conv-selected-pattern");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.SELECTED_PATTERN,
            "cip-pattern-selector",
            new SkillArtifactPayload.SelectedPatternPayload(
                new org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern(
                    "GP-01",
                    "Protected Request-Response",
                    "HTTP API",
                    null,
                    java.util.List.of(),
                    "http-trigger -> try-catch-finally-2"))));
    CompilerSkillInputSnapshot snapshot = contextBuilder.snapshotFromWorkspace(workspace);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("Selected golden pattern:"));
    assertTrue(message.contains("patternId: GP-01"));
    assertTrue(message.contains("Protected Request-Response"));
  }

  @Test
  void discoveryPromptAsksForRequirementBriefCapture() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-requirement-analyzer");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Generate a greeting chain", "", null, null, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("captureRequirementBrief"));
    assertTrue(message.contains("Do not call captureChainPlan or captureGraphPatch"));
  }

  @Test
  void requirementAnalyzerRoleIncludesApiHubLookupRules() throws Exception {
    String rolePrompt;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("prompts/roles/requirement-analyzer.md")) {
      rolePrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertTrue(rolePrompt.contains("searchApiOperations"));
    assertTrue(rolePrompt.contains("listApiHubPackages"));
    assertTrue(rolePrompt.contains("packageId:"));
    assertTrue(rolePrompt.contains("getApiHubDocument"));
  }

  @Test
  void graphConstructionRendersStructuredRequirementBrief() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-chain-generator");
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("conv-structured-brief");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.REQUIREMENT_BRIEF,
            "cip-requirement-analyzer",
            new SkillArtifactPayload.RequirementBriefPayload(
                new RequirementBrief(
                    "Call customer API",
                    java.util.List.of("packageId: pkg-1", "operationId: getCustomer"),
                    java.util.List.of("protocol: REST"),
                    java.util.List.of(),
                    java.util.List.of(),
                    ""))));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "user",
            new SkillArtifactPayload.RawUserRequestPayload("Build customer lookup", java.util.List.of())));

    CompilerSkillInputSnapshot snapshot = contextBuilder.snapshotFromWorkspace(workspace);
    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("Requirement brief:"));
    assertTrue(message.contains("packageId: pkg-1"));
    assertTrue(message.contains("operationId: getCustomer"));
    assertTrue(message.contains("protocol: REST"));
  }

  @Test
  void graphConstructionIncludesRuntimePackageIndexWithoutGoldenPatterns() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-chain-generator");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Generate a greeting chain", "Greeting endpoint", null, null, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("captureChainPlan"));
    if (message.contains("Compiler runtime package index:")) {
      assertTrue(message.contains("generator-packages"));
    }
    assertFalse(message.contains("You execute **cip-structure-generator**"));
    assertFalse(message.contains("Selected pattern:"));
  }

  @Test
  void generatorPromptIncludesBriefSignalsFromSkillAddons() {
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-trigger-generator");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot(
            "GET on internal route /greetings. No routing. No security.",
            "Open access without authentication or authorization.",
            null,
            null,
            null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertFalse(message.contains("Resolved request facts:"));
    assertTrue(message.contains("externalRoute=false"));
    assertTrue(message.contains("internal route"));
    assertTrue(message.contains("Compiler skill addon (skills/cip-trigger-generator.addon.md):"));
    assertFalse(message.contains("Compiler runtime package index:"));
  }

  @Test
  void serviceCallGeneratorKeepsNativeKafkaApplicableWhenCatalogCallsAreForbidden() {
    CompilerSkillDocument document =
        documentService.loadByCapabilityId("cip-service-call-generator");
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("kafka", "Kafka"),
            java.util.List.of(
                new ChainPlanNode(
                    "kafka-publish-event",
                    "kafka-sender-2",
                    "Publish event",
                    null,
                    null,
                    java.util.List.of())),
            java.util.List.of());
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot(
            "Use kafka-sender-2. No service calls. No APIHub.",
            "Publish through the native Kafka sender.",
            null,
            graph,
            null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("does not disable an owned native sender"));
    assertTrue(message.contains("configure it without"));
    assertTrue(message.contains("inventing a catalog binding"));
  }

  @Test
  void serviceCallPromptIdentifiesOwnedMappingsAndKeepsExplicitHooksAvailable() {
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-service-call-generator");
    ChainPlanGraph graph = new ChainPlanGraph(
        "1.0", new ChainSection("orders", "Orders"),
        java.util.List.of(
            new ChainPlanNode("map-orders", "script", "Map orders", null, null,
                java.util.List.of(new PlanProperty("mappingIntentId", "orders-map"))),
            new ChainPlanNode("orders", "service-call", "Orders", null, null, java.util.List.of()),
            new ChainPlanNode("map-response", "mapper-2", "Map response", null, null,
                java.util.List.of(new PlanProperty("mappingIntentId", "response-map"))),
            new ChainPlanNode("mapper-request", "mapper-2", "Map request", null, null,
                java.util.List.of(new PlanProperty("mappingIntentId", "request-map"))),
            new ChainPlanNode("payments", "service-call", "Payments", null, null, java.util.List.of()),
            new ChainPlanNode("ordinary-script", "script", "Other script", null, null, java.util.List.of()),
            new ChainPlanNode("unmapped", "service-call", "Other", null, null, java.util.List.of())),
        java.util.List.of(
            new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                "orders-in", "map-orders", "orders", null),
            new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                "orders-out", "orders", "map-response", null),
            new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                "payments-in", "mapper-request", "payments", null),
            new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                "unmapped-in", "ordinary-script", "unmapped", null)));
    CompilerSkillInputSnapshot snapshot = new CompilerSkillInputSnapshot(
        "Map client to customerId", "mappingIntentId=orders-map: $.client -> $.customerId",
        null, graph, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("service-call orders: incoming request body is prepared by map-orders"
        + " (mappingIntentId=orders-map)"), message);
    assertTrue(message.contains("service-call payments: incoming request body is prepared by mapper-request"
        + " (mappingIntentId=request-map)"), message);
    assertTrue(message.contains("service-call orders: response body is consumed by map-response"
        + " (mappingIntentId=response-map)"), message);
    assertTrue(message.contains("Use the target paths of that mapping as the incoming body contract"), message);
    assertTrue(message.contains("Preserve the source paths needed by that mapping"), message);
    assertTrue(message.contains("An independently requested inline hook remains valid"), message);
    assertFalse(message.contains("service-call unmapped: incoming request body is prepared"), message);
    var plan = new org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan(
        "service", "cip-service-call-generator",
        org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanStatus.READY,
        java.util.List.of(), java.util.List.of("orders"));
    String resumedMessage = contextBuilder.buildUserMessage("recovery", document, snapshot, plan);
    assertTrue(resumedMessage.contains("service-call orders: incoming request body is prepared by map-orders"
        + " (mappingIntentId=orders-map)"), resumedMessage);
    assertTrue(resumedMessage.contains("service-call orders: response body is consumed by map-response"
        + " (mappingIntentId=response-map)"), resumedMessage);
    assertTrue(resumedMessage.contains("$.client -> $.customerId"), resumedMessage);
  }

  @Test
  void serviceCallPromptIdentifiesOnlyItsAdjacentNativeResponseRetention() {
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-service-call-generator");
    var graph = new ChainPlanGraph("1.0", new ChainSection("orders", "Orders"), List.of(
        new ChainPlanNode("orders", "service-call", "Orders", null, null, List.of()),
        new ChainPlanNode("retain-order", "script", "Retain order", null, null,
            List.of(new PlanProperty("nativeRetentionSite", "true"))),
        new ChainPlanNode("payments", "service-call", "Payments", null, null, List.of()),
        new ChainPlanNode("ordinary", "script", "Ordinary script", null, null, List.of())),
        List.of(new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("orders-out", "orders", "retain-order", null),
            new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("payments-out", "payments", "ordinary", null)));
    var snapshot = new CompilerSkillInputSnapshot("Call services", "Retain order response", null, graph, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("service-call orders: response retention is assigned to retain-order"), message);
    assertTrue(message.contains("Do not add an after hook solely to retain that response"), message);
    assertTrue(message.contains("An independently requested inline hook remains valid"), message);
    assertFalse(message.contains("service-call payments: response retention is assigned"), message);
  }

  @Test
  void boundOwnershipShowsOnlyCurrentMapperAndHeaderPropertyTargets() {
    var document = documentService.loadByCapabilityId("cip-service-call-generator");
    var graph = ownedWorkGraph();
    var policy = new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy(
        false, false, java.util.Set.of("header-modification", "mapper-2"), java.util.Set.of(),
        java.util.Map.of("header-modification", java.util.Set.of("headerModificationToAdd", "headerModificationToRemove"),
            "mapper-2", java.util.Set.of("mappingDescription")));
    var snapshot = new CompilerSkillInputSnapshot("Configure headers", "Headers", null, graph, null);
    var message = contextBuilder.buildUserMessage("test", document, snapshot, stalePlan(),
        CaptureTool.CAPTURE_GRAPH_PATCH, ownedContext(graph, policy, List.of()));
    String slice = ownershipSlice(message);
    assertTrue(slice.contains("\"nodeId\":\"header\""), slice);
    assertTrue(slice.contains("\"headerModificationToAdd\""), slice);
    assertTrue(slice.contains("\"headerModificationToRemove\""), slice);
    assertTrue(slice.contains("\"nodeId\":\"mapper\""), slice);
    assertTrue(slice.contains("\"mappingDescription\""), slice);
    assertFalse(slice.contains("\"nodeId\":\"loop\""), slice);
    assertFalse(slice.contains("\"nodeId\":\"script\""), slice);
    assertFalse(message.contains("Active generator plan slice:"), message);
  }

  @Test
  void emptyOwnedPropertyTargetsDoNotImplyNoTopologyOrChainWork() {
    var document = documentService.loadByCapabilityId("cip-service-call-generator");
    var graph = ownedWorkGraph();
    var policy = new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy(
        true, true, java.util.Set.of("try"), java.util.Set.of("description"), java.util.Map.of());
    var snapshot = new CompilerSkillInputSnapshot("Wrap flow", "Wrap flow", null, graph, null);
    String slice = ownershipSlice(contextBuilder.buildUserMessage("test", document, snapshot, stalePlan(),
        CaptureTool.CAPTURE_GRAPH_PATCH, ownedContext(graph, policy, List.of())));
    assertTrue(slice.contains("\"propertyTargets\":[]"), slice);
    assertTrue(slice.contains("\"mayAddNodes\":true"), slice);
    assertTrue(slice.contains("\"mayAddEdges\":true"), slice);
    assertTrue(slice.contains("\"chainFields\":[\"description\"]"), slice);
    assertTrue(slice.contains("\"ownedNodeTypes\":[\"try\"]"), slice);
    assertTrue(slice.contains("An empty list does not establish inapplicability"), slice);
  }

  @Test
  void propertyOnlyOwnershipAndNamedEditScopeArePreserved() {
    var document = documentService.loadByCapabilityId("cip-service-call-generator");
    var graph = new ChainPlanGraph("1.0", new ChainSection("chain", "Chain"), List.of(
        new ChainPlanNode("entry-a", "http-trigger", "A", null, null, List.of()),
        new ChainPlanNode("entry-b", "http-trigger", "B", null, null, List.of())), List.of());
    var policy = new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy(
        false, false, java.util.Set.of(), java.util.Set.of(),
        java.util.Map.of("http-trigger", java.util.Set.of("accessControlType")));
    var snapshot = new CompilerSkillInputSnapshot("Edit A", "Edit A", null, graph, null, "Edit only entry-a");
    String slice = ownershipSlice(contextBuilder.buildUserMessage("test", document, snapshot, stalePlan(),
        CaptureTool.CAPTURE_GRAPH_PATCH, ownedContext(graph, policy, List.of("entry-a"))));
    assertTrue(slice.contains("\"nodeId\":\"entry-a\""), slice);
    assertTrue(slice.contains("\"accessControlType\""), slice);
    assertFalse(slice.contains("\"nodeId\":\"entry-b\""), slice);
    assertTrue(slice.contains("\"namedTargetNodeIds\":[\"entry-a\"]"), slice);
  }

  @Test
  void unboundPromptKeepsTheExistingManifestSlice() {
    var document = documentService.loadByCapabilityId("cip-service-call-generator");
    var snapshot = new CompilerSkillInputSnapshot("Call", "Call", null, ownedWorkGraph(), null);
    String message = contextBuilder.buildUserMessage("test", document, snapshot, stalePlan(), CaptureTool.CAPTURE_GRAPH_PATCH);
    assertTrue(message.contains("Active generator plan slice:"), message);
    assertTrue(message.contains("\"targetNodeIds\""), message);
    assertFalse(message.contains("Current generator ownership:"), message);
  }

  private static ChainPlanGraph ownedWorkGraph() {
    return new ChainPlanGraph("1.0", new ChainSection("chain", "Chain"), List.of(
        new ChainPlanNode("loop", "loop-2", "Loop", null, null, List.of()),
        new ChainPlanNode("script", "script", "Script", null, null, List.of()),
        new ChainPlanNode("header", "header-modification", "Header", null, null, List.of()),
        new ChainPlanNode("mapper", "mapper-2", "Mapper", null, null, List.of())), List.of());
  }

  private static org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan stalePlan() {
    return new org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan("generator", "skill",
        org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanStatus.READY,
        List.of("approved_producer"), List.of("loop", "script"));
  }

  private static org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext ownedContext(
      ChainPlanGraph graph, org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy ownership,
      List<String> targets) {
    return new org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext("run", "skill", "req",
        "graph", "compiler", "1", null, List.of(), graph, ownership, "", targets);
  }

  private static String ownershipSlice(String message) {
    int start = message.indexOf("Current generator ownership:");
    assertTrue(start >= 0, message);
    int end = message.indexOf("\n\n", start);
    return message.substring(start, end < 0 ? message.length() : end);
  }

  @Test
  void generatorPromptSerializesGraphAsCompactJson() {
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-security-generator");
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            java.util.List.of(
                new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, java.util.List.of())),
            java.util.List.of());
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Secure endpoint", "RBAC required", null, graph, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);
    String graphJson = extractGraphJsonBlock(message);

    assertFalse(graphJson.contains("\n"));
  }

  @Test
  void generatorPromptOmitsScriptBodyForNonScriptGenerator() {
    String scriptBody = "def greeting = 'hello from a long groovy script body'";
    ChainPlanGraph graph = graphWithScriptNode(scriptBody);
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-security-generator");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Secure endpoint", "RBAC required", null, graph, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);
    String graphJson = extractGraphJsonBlock(message);

    assertFalse(graphJson.contains(scriptBody));
    assertFalse(graphJson.contains("script body omitted"));
    assertTrue(graphJson.contains("\"script-1\""));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void generatorAndScriptRepairPromptsIncludeGroovyBindings(boolean standalone) {
    org.mockito.Mockito.doReturn(standalone).when(addonRepository).hasStandaloneSkills();
    CompilerSkillInputSnapshot snapshot = new CompilerSkillInputSnapshot(
        "Call the service", "Process its response", null, graphWithScriptNode(""), null);
    CompilerSkillDocument service = documentService.loadByCapabilityId("cip-service-call-generator");
    CompilerSkillDocument script = documentService.loadByCapabilityId("cip-script-generator");
    for (String message : java.util.List.of(
        contextBuilder.buildUserMessage(service, snapshot),
        contextBuilder.buildUserMessage(script, snapshot),
        contextBuilder.buildScriptRepairMessage("recovery", script, snapshot, java.util.List.of("script-1")))) {
      assertTrue(message.contains("For generated Groovy code, bindings include exchange, in, body, headers, variables, and exchangeProperties"), message);
      assertTrue(message.contains("Use the documented runtime bindings or explicitly declared local variables"), message);
      assertTrue(message.contains("message is not an implicit binding"), message);
      assertTrue(message.contains("access exchange.getMessage() or exchange.in, or declare a local alias"), message);
      assertTrue(message.contains("standalone and inline scripts"), message);
    }
  }

  @Test
  void scriptGeneratorPromptKeepsFullScriptBody() {
    String scriptBody = "def greeting = 'hello from a long groovy script body'";
    ChainPlanGraph graph = graphWithScriptNode(scriptBody);
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-script-generator");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Fill script bodies", "Static response", null, graph, null);

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains(scriptBody));
    assertFalse(message.contains("<script body omitted,"));
  }

  @Test
  void scriptRepairMessageIncludesAddonGuidance() {
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-script-generator");
    ChainPlanGraph graph = graphWithScriptNode("");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Fill script bodies", "Static response", null, graph, null);

    String message =
        contextBuilder.buildScriptRepairMessage(
            "conversation-1", document, snapshot, java.util.List.of("script-1"));

    assertTrue(message.contains("Compiler skill addon (skills/cip-script-generator.addon.md):"));
    assertTrue(message.contains("Catch scripts vs try-path scripts"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void scriptProductionAndRepairReceiveTheSameExecutableLiteralContract(boolean standalone) {
    org.mockito.Mockito.doReturn(standalone).when(addonRepository).hasStandaloneSkills();
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-script-generator");
    String behavior = "Copy the original identifier to the result and prepend the approved label.";
    String brief = "{\"text\":\"" + behavior + "\",\"exactValues\":[\"Ready \",\"a=b\"]}";
    CompilerSkillInputSnapshot snapshot = new CompilerSkillInputSnapshot(
        behavior, brief, null, graphWithScriptNode("exchange.in.body = 'a=b'"), null);
    var renderer = io.quarkus.qute.Engine.builder().addDefaults()
        .addParserHook(new io.quarkiverse.langchain4j.QuarkusPromptTemplateFactory
            .MustacheTemplateVariableStyleParserHook()).build();

    for (String encoded : java.util.List.of(
        contextBuilder.buildUserMessage(document, snapshot),
        contextBuilder.buildScriptRepairMessage(
            "conversation-1", document, snapshot, java.util.List.of("script-1")),
        contextBuilder.buildScriptRepairMessage(
            "conversation-1", document, snapshot, java.util.List.of("script-1"), true))) {
      String message = renderer.parse(encoded).render();
      assertTrue(message.contains(behavior), message);
      assertTrue(message.contains(brief), message);
      assertTrue(message.contains("including whitespace and quotation marks"), message);
      assertTrue(message.contains("runtime expressions outside that constant"), message);
      assertTrue(message.contains("unused constants"), message);
      assertTrue(message.contains("Do not remove or rewrite approved exactValues"), message);
      assertTrue(message.contains("does not authorize changing a required runtime output value"), message);
      assertTrue(message.contains("field binding. Preserve both the approved behavior"), message);
      assertTrue(message.contains("incompatibility in the capture rationale"), message);
      assertTrue(message.contains("Use only approved success and failure predicates"), message);
      assertTrue(message.contains("Missing, null, empty, blank, false, and numeric zero are distinct"), message);
      assertFalse(message.contains("def failed = (status != null && status >= 400)"), message);
    }
  }

  @Test
  void scriptEditPromptIncludesOnlySelectedOriginalBodiesAndEditContext() {
    String original = "def data = exchange.in.body\nreturn data";
    ChainPlanGraph fixture = graphWithScriptNode(original);
    var nodes = new java.util.ArrayList<>(fixture.nodes());
    nodes.add(new org.qubership.integration.platform.ai.plan.model.ChainPlanNode("unselected", "script",
        "Unselected", null, null, java.util.List.of(
            new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", "return 'PRIVATE_UNSELECTED'"))));
    ChainPlanGraph graph = new ChainPlanGraph(fixture.schemaVersion(), fixture.chain(), nodes, fixture.edges());
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-script-generator");
    var snapshot = new CompilerSkillInputSnapshot("Add the current date", "Static response", null, graph, null,
        "action: CONFIGURE\nproperty keys: script");
    String encoded = contextBuilder.buildScriptRepairMessage("conversation-1", document, snapshot,
        java.util.List.of("script-1"), true);
    var renderer = io.quarkus.qute.Engine.builder().addDefaults()
        .addParserHook(new io.quarkiverse.langchain4j.QuarkusPromptTemplateFactory
            .MustacheTemplateVariableStyleParserHook()).build();
    String message = renderer.parse(encoded).render();
    String json = message.substring(message.indexOf("Original script bodies:\n")
        + "Original script bodies:\n".length(), message.indexOf("\n\nRequired script node IDs:"));
    try {
      assertEquals(original, new ObjectMapper().readTree(json).get(0).path("script").asText());
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
    assertTrue(message.contains("action: CONFIGURE"));
    assertTrue(message.contains("Add the current date"));
    org.junit.jupiter.api.Assertions.assertAll("edit request preserves script authority",
        () -> assertTrue(message.contains("Original bodies are reference evidence"), message),
        () -> assertTrue(message.contains("A supplied-body edit requires its newly approved exact body"), message),
        () -> assertTrue(message.contains("report it as unsupported; do not generate replacement code"), message),
        () -> assertFalse(message.contains("as the basis for the requested change. Preserve unrelated logic"), message));
    assertTrue(message.contains("Required script node IDs"));
    assertFalse(message.contains("PRIVATE_UNSELECTED"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"BEHAVIOR", "CONSTRAINT"})
  void scriptGenerationContextIncludesEveryApprovedFactOnTheSameOwner(String kind) throws Exception {
    var original = org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures
        .linearOrdersWithCompleteTask();
    String node = org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures.COMPLETE_TASK_NODE_ID;
    var first = org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures
        .generatedBehavior(node, "Return a greeting.");
    var json = new ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode data = json.valueToTree(first);
    data.put("sourceFactId", "supplementary").put("kind", kind).put("text", "Preserve the approved supplementary behavior.");
    var second = json.treeToValue(data, org.qubership.integration.platform.ai.plan.RequirementFact.class);
    var brief = new RequirementBrief("Greeting", java.util.List.of(), java.util.List.of(), java.util.List.of(),
        java.util.List.of(), "Greeting").withFacts(java.util.List.of(first, second));
    var revision = org.qubership.integration.platform.ai.plan.mapping.ScriptAuthorityTestFixtures
        .attachSuppliedFacts(original, brief);
    var graph = new ChainPlanGraph("1.0", new ChainSection("greeting", "Greeting"), java.util.List.of(
        new ChainPlanNode(node, "script", "Greeting", null, null, java.util.List.of())), java.util.List.of());
    String context = contextBuilder.renderBehaviorScriptGenerationContext(brief, java.util.List.of(node), graph, revision);
    org.junit.jupiter.api.Assertions.assertAll("all approved Script facts",
        () -> assertTrue(context.contains(first.sourceFactId() + ": Return a greeting."), context),
        () -> assertTrue(context.contains("supplementary: Preserve the approved supplementary behavior."), context));
  }

  @Test
  void nativeScriptContextDoesNotAuthorizeExtraBehaviorGeneration() {
    String context = contextBuilder.renderBehaviorScriptGenerationContext(
        null, java.util.List.of("script-1"), graphWithScriptNode(""), null);

    org.junit.jupiter.api.Assertions.assertAll("native script task instructions",
        () -> assertTrue(context.contains("Native script tasks:"), context),
        () -> assertTrue(context.contains("frozen native mapping or compiler-derived retention plan"), context),
        () -> assertTrue(context.contains("does not authorize extra generated behavior"), context),
        () -> assertTrue(context.contains("Preserve exact source-bound supplied bodies"), context),
        () -> assertFalse(context.contains("Behavior-owned script generation"), context));
  }

  @Test
  void scriptRepairMessageAppendsMappingGenerationContext() {
    CompilerSkillDocument document = documentService.loadByCapabilityId("cip-script-generator");
    ChainPlanGraph graph = graphWithScriptNode("");
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot("Fill script bodies", "Static response", null, graph, null)
            .withMappingGenerationContext(
                "Mapping generation:\n\nmappingIntentId: map-init\n");

    String message =
        contextBuilder.buildScriptRepairMessage(
            "conversation-1", document, snapshot, java.util.List.of("script-1"));

    assertTrue(message.contains("Mapping generation:"));
    assertTrue(message.contains("mappingIntentId: map-init"));
  }

  @Test
  void scriptRepairRoleRequiresGroovyNotJavaScript() throws Exception {
    String rolePrompt;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("prompts/roles/script-body-repair.md")) {
      rolePrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertTrue(rolePrompt.contains("Groovy"));
    assertFalse(rolePrompt.contains("JavaScript"));
  }

  private static ChainPlanGraph graphWithScriptNode(String scriptBody) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "Demo"),
        java.util.List.of(
            new ChainPlanNode(
                "script-1",
                "script",
                "Script",
                null,
                null,
                java.util.List.of(new PlanProperty("script", scriptBody)))),
        java.util.List.of());
  }

  private static String extractGraphJsonBlock(String message) {
    String marker = "Current ChainPlanGraph JSON:\n";
    int start = message.indexOf(marker);
    assertTrue(start >= 0, "graph JSON marker missing");
    start += marker.length();
    int end = message.indexOf("\n\n", start);
    assertTrue(end > start, "graph JSON block missing");
    return message.substring(start, end);
  }

  private static String extractSkillAddonBlock(String message, String relativePath) {
    String marker = "Compiler skill addon (" + relativePath + "):\n";
    int start = message.indexOf(marker);
    assertTrue(start >= 0, "skill addon block missing: " + relativePath);
    start += marker.length();
    int end = message.indexOf("\n\nCompiler skill document (", start);
    if (end < 0) {
      end = message.indexOf("\n\nGraphPatch example (", start);
    }
    if (end < 0) {
      end = message.indexOf("\n\nExample (", start);
    }
    if (end < 0) {
      end = message.length();
    }
    return message.substring(start, end);
  }

  @Test
  void validatorPromptAsksForValidationCaptureAndGraphContext() {
    CompilerSkillDocument document = documentService.loadByCapabilityId("plan-validator");
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            java.util.List.of(
                new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, java.util.List.of())),
            java.util.List.of());
    CompilerSkillInputSnapshot snapshot =
        new CompilerSkillInputSnapshot(
            "Build demo", "goal: demo", null, graph, "- cip-security-generator: READY");

    String message = contextBuilder.buildUserMessage(document, snapshot);

    assertTrue(message.contains("captureValidationResult"));
    assertTrue(message.contains("Do not call captureChainPlan or captureGraphPatch"));
    assertTrue(message.contains("Current ChainPlanGraph JSON:"));
    assertTrue(message.contains("Generator plan manifest:"));
    assertTrue(message.contains("cip-security-generator: READY"));
  }
}
