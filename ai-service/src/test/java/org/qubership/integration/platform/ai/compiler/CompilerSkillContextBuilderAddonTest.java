package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonBuildSupport;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemQipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class CompilerSkillContextBuilderAddonTest {

  private CompilerSkillContextBuilder contextBuilder;
  private CompilerSkillDocumentService documentService;
  private String previousAddonPackRoot;

  @BeforeEach
  void setUp(@TempDir Path outputDir, @TempDir Path addonRoot) throws Exception {
    previousAddonPackRoot = System.getProperty("qip.ai.qipknowledge.addon-pack-root");
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    Path fixtureAddonRoot = QipKnowledgePackFixturePaths.addonRoot();
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
    QipKnowledgePackBuildGenerator.generate(QipKnowledgePackFixturePaths.packRoot(), outputDir);
    CompilerSkillAddonBuildSupport.materialize(
        addonRoot, outputDir.resolve(QipKnowledgePackFixturePaths.PACK_DIR));

    QipKnowledgePackVersion version = QipKnowledgePackFixturePaths.packVersion();
    FilesystemQipKnowledgePackRepository repository =
        new FilesystemQipKnowledgePackRepository(outputDir, version);
    CompilerSkillAddonRepository addonRepository =
        CompilerSkillAddonRepository.forFilesystem(outputDir, version, getClass().getClassLoader());
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
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    QipKnowledgePackBuildGenerator.generate(
        QipKnowledgePackFixturePaths.packRoot(),
        outputDir,
        QipKnowledgePackFixturePaths.addonRoot());
    QipKnowledgePackVersion version = QipKnowledgePackFixturePaths.packVersion();
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
