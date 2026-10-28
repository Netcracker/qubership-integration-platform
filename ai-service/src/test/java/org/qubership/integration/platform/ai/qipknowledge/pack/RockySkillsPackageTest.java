package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.CompilerSkillInputSnapshot;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntimeEligibility;
import org.qubership.integration.platform.ai.compiler.addon.AddonRuntimeMetadataParser;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.policy.AddonReadinessParser;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicyParseException;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;

@org.junit.jupiter.api.Tag("certified-pack")
class RockySkillsPackageTest {
  private static final Path PACK = Path.of(System.getProperty("qip.test.pack-root", "../.private/rocky-skills")).toAbsolutePath().normalize();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void trackedSourcesMatchTheReleaseManifest() throws Exception {
    var manifest = JSON.readTree(PACK.resolve("manifest.json").toFile());
    assertEquals(34, manifest.path("activePromptIds").size());
    var ids = new HashSet<String>();
    manifest.path("activePromptIds").forEach(id -> assertTrue(ids.add(id.asText())));
    manifest.path("maintenanceSkillIds").forEach(id -> assertTrue(ids.add(id.asText())));
    assertEquals(35, ids.size());
    for (String id : ids) {
      assertTrue(Files.isRegularFile(PACK.resolve("skills/" + id + "/SKILL.md")), id);
    }
    var fields = manifest.path("files").fields();
    while (fields.hasNext()) {
      var file = fields.next();
      Path path = PACK.resolve(file.getKey()).normalize();
      assertTrue(path.startsWith(PACK), file.getKey());
      String actual =
          HexFormat.of().formatHex(
              MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
      assertEquals(file.getValue().path("sha256").asText(), actual, file.getKey());
    }
    assertEquals("integration-platform-skills", CipKnowledgePackVersion.fromPath(PACK).normalized());
    assertTrue(ids.contains("cip-mcp-service-generator"));
    assertFalse(ids.contains("cip-chain-generator"));
    assertTrue(ids.contains("cip-utility-rocky-skills-update"));
    try (var roots = Files.list(PACK)) {
      var expectedRoots = new HashSet<>(java.util.Set.of("skills", "runtime", "manifest.json"));
      if (Files.isDirectory(PACK.resolve("testing"))) expectedRoots.add("testing");
      assertEquals(expectedRoots,
          roots.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
    }
    try (var paths = Files.walk(PACK)) {
      var actual = paths.filter(Files::isRegularFile).map(path -> PACK.relativize(path).toString())
          .filter(path -> path.startsWith("skills/") || path.startsWith("runtime/"))
          .collect(java.util.stream.Collectors.toSet());
      assertEquals(actual, JSON.convertValue(manifest.path("files"), java.util.Map.class).keySet());
    }
  }

  @Test
  void adaptedPackagePreservesExecutionContracts(@TempDir Path output) throws Exception {
    CipKnowledgePackBuildGenerator.generate(PACK, output, PACK.resolve("runtime"));
    var before = JSON.readTree(PACK.resolveSibling("rocky-skills-maintenance").resolve("extraction-baseline.json").toFile());
    Path version = output.resolve("integration-platform-skills");
    var policy = JSON.readTree(version.resolve("compiler-generator-policy.json").toFile());
    assertEquals(before.path("policy").path("generators"), policy.path("generators"),
        "generator selection, ordering, ownership, or readiness changed");
    var pipeline = (ObjectNode) JSON.readTree(version.resolve("compiler-pipeline-index.json").toFile());
    var baseline = before.path("pipeline").deepCopy();
    normalizeSourcePins(pipeline);
    normalizeSourcePins(baseline);
    assertEquals(
        JSON.treeToValue(baseline, CompilerPipelineIndex.class),
        JSON.treeToValue(pipeline, CompilerPipelineIndex.class),
        "DAG, captures, readiness, or ownership changed");
    var manifest = JSON.readTree(PACK.resolve("manifest.json").toFile());
    var provenance = JSON.readTree(PACK.resolveSibling("rocky-skills-maintenance").resolve("provenance.json").toFile());
    assertEquals(27, provenance.path("ccsAdaptations").size());
    for (var id : manifest.path("activePromptIds")) {
      String source = "skills/" + id.asText() + "/SKILL.md";
      assertEquals(provenance.path("files").path(source).path("sha256"),
          manifest.path("files").path(source).path("sha256"), source);
    }
    var repository = new FilesystemCipKnowledgePackRepository(output, CipKnowledgePackVersion.fromPath(PACK));
    var metadata = CompilerSkillAddonRepository.forFilesystem(
        output, CipKnowledgePackVersion.fromPath(PACK), getClass().getClassLoader());
    assertTrue(metadata.hasStandaloneSkills());
    assertFalse(Files.exists(PACK.resolve("addons")));
    assertFalse(Files.exists(version.resolve("addons")));
    assertTrue(metadata.readGlobalDataDocument("global/materialization-requirements.yaml").isPresent());
    var documents = new CompilerSkillDocumentService(repository);
    var knowledge = FakeKnowledgeClient.defaultFixture();
    var builder = new CompilerSkillContextBuilder(JSON, repository, metadata,
        mock(CompilerSkillRuntimeEligibility.class), knowledge, knowledge);
    var snapshot = new CompilerSkillInputSnapshot("Configure requested behavior", "Approved brief", null, null, null);
    for (var idNode : manifest.path("activePromptIds")) {
      String id = idNode.asText();
      if (id.startsWith("cip-conf-")) continue;
      assertFalse(metadata.loadForSkill(id).hasContent(), id);
      var definition = JSON.readTree(PACK.resolve("runtime/skills/" + id + ".json").toFile());
      assertEquals(JSON.treeToValue(definition.path("runtime"),
          org.qubership.integration.platform.ai.compiler.addon.AddonRuntimeMetadata.class),
          metadata.loadRuntimeMetadata(id).orElse(null), id);
      var document = documents.loadByCapabilityId(id);
      String prompt = builder.buildUserMessage(document, snapshot);
      assertEquals(1, prompt.split("Skill instructions:", -1).length - 1, id);
      for (String forbidden : List.of("addon", "## Upstream", "## Runtime metadata", "## Readiness signals",
          "generator-id:", "checksum:", "CCS domain rules", "authoritative", "sourceSha256")) {
        assertFalse(prompt.contains(forbidden), id + ": " + forbidden);
      }
      assertTrue(document.markdown().contains("## "), id);
    }
    assertEquals(CaptureTool.REPAIR_SCRIPT_BODIES,
        metadata.loadRuntimeMetadata("cip-script-generator").orElseThrow().captureTool());
    String repair = builder.buildScriptRepairMessage(null, documents.loadByCapabilityId("cip-script-generator"),
        snapshot, List.of());
    assertTrue(repair.contains("Skill instructions:"));
    assertEquals(1, repair.split("### Technical context headers", -1).length - 1);
    assertFalse(repair.contains("addon"));
    var original = new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
        "script-a", "script", "Approved script", null, null,
        List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("script", "return 'keep-me'")));
    var graph = new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph(
        "1", new org.qubership.integration.platform.ai.plan.model.ChainSection("Approved", ""),
        List.of(original), List.of());
    String editRepair = builder.buildScriptRepairMessage(null,
        documents.loadByCapabilityId("cip-script-generator"),
        new CompilerSkillInputSnapshot("Edit script", "Change requested field only", null, graph, null),
        List.of("script-a"), true);
    assertTrue(editRepair.contains("keep-me"));
    assertTrue(editRepair.contains("Original script nodes:"));
    String scoped = builder.buildUserMessage(null, documents.loadByCapabilityId("cip-security-generator"),
        snapshot, new org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan(
            "GEN-999", "cip-security-generator", null, List.of("always_ready"), List.of("http-a")));
    assertTrue(scoped.contains("http-a"));
    assertFalse(scoped.contains("GEN-999"));
    assertFalse(scoped.contains("matchedSignals"));
    String discovery = new org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder(
        documents, metadata, null).wrap("standalone", "Explain retries", "ru");
    assertTrue(discovery.contains("Skill instructions:"));
    assertTrue(discovery.contains("Pinned response locale: ru"));
    assertFalse(discovery.contains("addon"));
    assertFalse(discovery.contains("<service-runtime-envelope>"));
    assertFalse(discovery.contains("version="));
    assertEquals(34, repository.loadRagIngestionManifest().chunks().size());
    assertTrue(repository.loadCapabilityRegistry().capabilities().stream()
        .noneMatch(capability -> capability.id().equals("cip-utility-rocky-skills-update")));
    assertTrue(repository.loadRagIngestionManifest().chunks().stream()
        .noneMatch(chunk -> chunk.sourcePath().startsWith("runtime/")
            || chunk.sourcePath().startsWith("tests/")));
  }

  @Test
  void modifiedMetadataFailsBeforeIndexGeneration(@TempDir Path root) throws Exception {
    Path copy = root.resolve("rocky-skills");
    try (var paths = Files.walk(PACK)) {
      for (Path source : paths.toList()) {
        Path target = copy.resolve(PACK.relativize(source));
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target);
      }
    }
    Files.writeString(copy.resolve("runtime/generator-contracts.json"), "[]");
    var error = assertThrows(IllegalArgumentException.class,
        () -> CipKnowledgePackBuildGenerator.generate(copy, root.resolve("output"), copy.resolve("runtime")));
    assertTrue(error.getMessage().contains("checksum mismatch"));
    assertFalse(Files.exists(root.resolve("output")));
    Files.copy(PACK.resolve("runtime/generator-contracts.json"), copy.resolve("runtime/generator-contracts.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Path unlisted = copy.resolve("skills/unlisted/SKILL.md");
    Files.createDirectories(unlisted.getParent());
    Files.writeString(unlisted, "# Unlisted skill");
    error = assertThrows(IllegalArgumentException.class,
        () -> CipKnowledgePackBuildGenerator.generate(copy, root.resolve("output"), copy.resolve("runtime")));
    assertTrue(error.getMessage().contains("Undeclared Rocky source"));
  }

  @Test
  void malformedMachineDefinitionsFailClosed() {
    var runtime = new AddonRuntimeMetadataParser();
    assertThrows(CompilerGeneratorPolicyParseException.class, () -> runtime.parseAddonContent(
        "{\"runtime\":{\"promoted\":true,\"category\":\"runtime\",\"captureTool\":\"UNKNOWN\"}}", "test.json"));
    assertThrows(CompilerGeneratorPolicyParseException.class, () -> new AddonReadinessParser().parseAddonContent(
        "{\"readiness\":{\"mode\":\"ai-service-adapter\",\"signals\":[]}}", "test.json"));
  }

  private static void normalizeSourcePins(JsonNode pipeline) {
    ((ObjectNode) pipeline).remove(List.of("packageIdentity", "sources", "sourceDigests"));
    for (var node : pipeline.path("nodes")) {
      ((ObjectNode) node).remove(List.of("addonSha256", "skillSha256"));
    }
    for (var entry : pipeline.path("entries")) {
      ((ObjectNode) entry).remove("sourceSha256");
    }
  }
}
