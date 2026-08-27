package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class CompilerSkillAddonBuildSupportTest {

  @Test
  void writesEmptyIndexWhenAddonPackIsMissing(@TempDir Path outputDir) throws Exception {
    CompilerSkillAddonBuildSupport.materialize(null, outputDir);

    Path indexFile =
        outputDir
            .resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR)
            .resolve(CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE);
    assertTrue(Files.isRegularFile(indexFile));
    assertEquals(
        CompilerSkillAddonIndex.empty(),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(Files.readString(indexFile), CompilerSkillAddonIndex.class));
  }

  @Test
  void copiesAddonPackAndBuildsIndex(@TempDir Path addonRoot, @TempDir Path outputDir)
      throws Exception {
    Files.createDirectories(addonRoot.resolve("global"));
    Files.writeString(
        addonRoot.resolve("global/runtime-contract.md"), "# Runtime contract\n");
    Files.writeString(
        addonRoot.resolve("global/materialization-requirements.yaml"),
        "version: 1\nelementRequirements: {}\n");
    Files.createDirectories(addonRoot.resolve("skills"));
    Files.writeString(
        addonRoot.resolve("skills/cip-security-generator.addon.md"),
        addon("Security addon", "captureGraphPatch"));
    Files.createDirectories(addonRoot.resolve("examples/cip-security-generator"));
    Files.writeString(
        addonRoot.resolve("examples/cip-security-generator/rbac.json"),
        "{\"patchId\":\"rbac\"}\n");

    CompilerSkillAddonBuildSupport.materialize(addonRoot, outputDir);

    Path addonsDir = outputDir.resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR);
    assertTrue(Files.isRegularFile(addonsDir.resolve("global/runtime-contract.md")));
    assertTrue(
        Files.isRegularFile(addonsDir.resolve("skills/cip-security-generator.addon.md")));

    CompilerSkillAddonIndex index =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(
                Files.readString(addonsDir.resolve(CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE)),
                CompilerSkillAddonIndex.class);
    assertEquals(1, index.globalDocuments().size());
    assertEquals("global/runtime-contract.md", index.globalDocuments().get(0));
    assertEquals(1, index.globalDataDocuments().size());
    assertEquals(
        "global/materialization-requirements.yaml", index.globalDataDocuments().get(0));
    assertTrue(index.skills().containsKey("cip-security-generator"));
    assertEquals(
        "skills/cip-security-generator.addon.md",
        index.skills().get("cip-security-generator").addonDocument());
    assertEquals(
        CaptureTool.CAPTURE_GRAPH_PATCH,
        index.skills().get("cip-security-generator").runtimeMetadata().captureTool());
    assertEquals(
        "examples/cip-security-generator/rbac.json",
        index.skills().get("cip-security-generator").examples().get(0));
  }

  @Test
  void materializeCanRunTwiceIntoSameOutputDir(@TempDir Path addonRoot, @TempDir Path outputDir)
      throws Exception {
    Files.createDirectories(addonRoot.resolve("skills"));
    Files.writeString(
        addonRoot.resolve("skills/cip-security-generator.addon.md"),
        addon("Security", "captureGraphPatch"));

    CompilerSkillAddonBuildSupport.materialize(addonRoot, outputDir);
    CompilerSkillAddonBuildSupport.materialize(addonRoot, outputDir);

    Path addonFile =
        outputDir
            .resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR)
            .resolve("skills/cip-security-generator.addon.md");
    assertTrue(Files.isRegularFile(addonFile));
    assertEquals(addon("Security", "captureGraphPatch"), Files.readString(addonFile));
  }

  @Test
  void rejectsSkillAddonWithoutCaptureTool(@TempDir Path addonRoot, @TempDir Path outputDir)
      throws Exception {
    Files.createDirectories(addonRoot.resolve("skills"));
    Files.writeString(
        addonRoot.resolve("skills/cip-security-generator.addon.md"),
        """
        # Security

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: runtime
          runtime-skill: true
        ```
        """);

    assertThrows(
        IllegalArgumentException.class,
        () -> CompilerSkillAddonBuildSupport.materialize(addonRoot, outputDir));
  }

  @Test
  void indexesProcessAddonWithoutRuntimeMetadata(@TempDir Path addonRoot, @TempDir Path outputDir)
      throws Exception {
    Files.createDirectories(addonRoot.resolve("skills"));
    Files.writeString(
        addonRoot.resolve("skills/brainstorming.addon.md"),
        """
        # brainstorming addon

        Process-only gather overrides without compiler capture routing.
        """);

    CompilerSkillAddonBuildSupport.materialize(addonRoot, outputDir);

    Path addonsDir = outputDir.resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR);
    CompilerSkillAddonIndex index =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(
                Files.readString(addonsDir.resolve(CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE)),
                CompilerSkillAddonIndex.class);
    assertTrue(index.skills().containsKey("brainstorming"));
    assertEquals(null, index.skills().get("brainstorming").runtimeMetadata());
  }

  @Test
  void indexesDesignProcessAddonsWithoutCaptureTool(
      @TempDir Path addonRoot, @TempDir Path outputDir) throws Exception {
    Files.createDirectories(addonRoot.resolve("skills"));
    Files.writeString(
        addonRoot.resolve("skills/cip-design-planner.addon.md"),
        """
        # cip-design-planner addon

        - Input artifacts: `IDS_DOCUMENT`, `RUN_MANIFEST`
        - Output artifacts: `DESIGN_PLAN_REPORT`

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: runtime
          runtime-skill: true
        ```
        """);
    Files.writeString(
        addonRoot.resolve("skills/cip-design-executor.addon.md"),
        """
        # cip-design-executor addon

        - Input artifacts: `DESIGN_EXECUTION_PLAN`, `APPROVAL_RECORD`
        - Output artifacts: `VALIDATED_EXECUTION_BUNDLE`

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: runtime
          runtime-skill: true
        ```
        """);

    CompilerSkillAddonBuildSupport.materialize(addonRoot, outputDir);

    Path addonsDir = outputDir.resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR);
    CompilerSkillAddonIndex index =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(
                Files.readString(addonsDir.resolve(CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE)),
                CompilerSkillAddonIndex.class);
    assertTrue(index.skills().get("cip-design-planner").runtimeMetadata().promoted());
    assertEquals(
        null, index.skills().get("cip-design-planner").runtimeMetadata().captureTool());
    assertTrue(index.skills().get("cip-design-executor").runtimeMetadata().promoted());
    assertEquals(
        null, index.skills().get("cip-design-executor").runtimeMetadata().captureTool());
  }

  @Test
  void loadForSkillFailsWhenAddonIndexIsMissing(@TempDir Path outputDir) {
    CompilerSkillAddonRepository repository =
        CompilerSkillAddonRepository.forFilesystem(
            outputDir, new QipKnowledgePackVersion("v1", "v1"), getClass().getClassLoader());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> repository.loadForSkill("cip-design-executor"));
    assertTrue(error.getMessage().contains("addon index is missing"));
  }

  @Test
  void loadedDocumentsIncludeContentDigest(@TempDir Path addonRoot, @TempDir Path outputDir)
      throws Exception {
    Files.createDirectories(addonRoot.resolve("skills"));
    String content = addon("Security addon", "captureGraphPatch");
    Files.writeString(addonRoot.resolve("skills/cip-security-generator.addon.md"), content);

    Path versionDir = outputDir.resolve("v1");
    CompilerSkillAddonBuildSupport.materialize(addonRoot, versionDir);

    CompilerSkillAddonRepository repository =
        CompilerSkillAddonRepository.forFilesystem(
            outputDir, new QipKnowledgePackVersion("v1", "v1"), getClass().getClassLoader());
    CompilerSkillAddonDocument document =
        repository.loadForSkill("cip-security-generator").skillAddon();

    assertEquals("skills/cip-security-generator.addon.md", document.relativePath());
    assertEquals(content, document.content());
    assertEquals(CompilerSkillAddonDocument.sha256(content), document.sha256());
  }

  private static String addon(String title, String captureTool) {
    return """
        # %s

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: runtime
          runtime-skill: true
          capture:
            tool: %s
        ```
        """
        .formatted(title, captureTool);
  }
}
