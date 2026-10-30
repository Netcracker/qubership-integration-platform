package org.qubership.integration.platform.ai.qipknowledge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

@Tag("certified-pack")
class CompilerContractKnowledgeAlignmentTest {

  private static final String IDS_COMPILER_EXTRACTION =
      "parse the IDS into compiler input";
  private static final String COMPILER_SOURCE_CHOICE = "GENERATE/DERIVE/PROVIDE";

  @Test
  void knowledgeIndexPinsCompilerContractDigestAndAsyncCardinality(@TempDir Path outputDir)
      throws Exception {
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    CipKnowledgePackBuildGenerator.generate(
        CipKnowledgePackFixturePaths.packRoot(),
        outputDir,
        CipKnowledgePackFixturePaths.addonRoot());

    CipKnowledgePackRepository repository =
        new FilesystemCipKnowledgePackRepository(
            outputDir, CipKnowledgePackFixturePaths.packVersion());
    CipKnowledgePackManifest index = repository.loadManifest();

    assertEquals(contract.sha256(), index.compilerContractSha256());
    assertEquals(contract.contractVersion(), index.compilerContractVersion());
    assertEquals(1, runtimeDescriptor("split-async-2").minimumChildren());
    assertEquals(1, contract.topology().get("split-async-2").minimumBranches());
    assertEquals(
        contract.elements().get("split-async-2").runtimeDescriptor().minimumChildren(),
        runtimeDescriptor("split-async-2").minimumChildren());

    assertEquals(contract.sha256(), classpathRepository().requireCompilerContractDigest());
    assertFalse(index.addonSha256().isEmpty());
    assertTrue(index.addonSha256().containsKey("cip-design-executor"));
    assertTrue(index.addonSha256().containsKey("cip-structure-generator"));
  }

  @Test
  void standaloneSkillsReuseSemanticCompilerSeed() throws Exception {
    String generatorAddon = readPackSkill("cip-conf-semantic-design");
    String structureAddon = readPackSkill("cip-structure-generator");
    String executorAddon = readPackSkill("cip-conf-semantic-design");
    List<String> addons = List.of(generatorAddon, structureAddon, executorAddon);

    assertTrue(
        addons.stream().noneMatch(text -> text.contains(IDS_COMPILER_EXTRACTION)),
        "Skills must not treat IDS markdown as compiler input");
    assertTrue(
        addons.stream().noneMatch(CompilerContractKnowledgeAlignmentTest::hasCompilerSourceChoice),
        "Deprecated GENERATE/DERIVE/PROVIDE compiler-source choice must be absent");
    assertTrue(
        generatorAddon.toLowerCase(Locale.ROOT).contains("server owns"),
        "cip-design-generator must describe IDS as a derived approval view");
    assertTrue(
        structureAddon.toLowerCase(Locale.ROOT).contains("semantic compiler"),
        "cip-structure-generator must use the canonical graph seed from the semantic compiler");
    assertTrue(
        executorAddon.contains("semantic node"),
        "cip-design-executor must map semantic node ownership");
    assertTrue(
        executorAddon.contains("execution edge"),
        "cip-design-executor must map execution edge ownership");

    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    for (String addonId : contract.requiredAddons()) {
      assertTrue(
          Files.isRegularFile(CipKnowledgePackFixturePaths.addonRoot().resolve("skills/" + addonId + ".json")),
          "Required runtime definition is missing: " + addonId);
    }
    for (String fragment : contract.requiredKnowledgeFragments()) {
      assertTrue(
          Files.isRegularFile(packKnowledgeFile(fragment)),
          "Required pack knowledge fragment is missing: " + fragment);
    }
  }

  @Test
  void everyCompilerContractRuleMapsToRuntimeDefinitionAndDescriptor() throws Exception {
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    var rows = new ObjectMapper().readTree(
        CipKnowledgePackFixturePaths.packRoot().resolveSibling("rocky-skills-maintenance").resolve("contract-alignment.json").toFile());

    Set<String> requiredIds = new LinkedHashSet<>();
    requiredIds.addAll(contract.topology().keySet());
    requiredIds.addAll(contract.elements().keySet());
    for (String ruleId : requiredIds) {
      var row = rows.get(ruleId);
      assertNotNull(row, "Unmapped compiler contract rule: " + ruleId);
      String owner = row.path("owner").asText();
      assertFalse(owner.isBlank(), "Owner is missing for " + ruleId);
      boolean unsupportedOwner = "unsupported".equals(owner);
      if (unsupportedOwner) {
        assertTrue(contract.topology().containsKey(ruleId) && !contract.topology().get(ruleId).supported(),
            "Supported rule cannot have an unsupported owner: " + ruleId);
      } else {
        assertTrue(Files.isRegularFile(CipKnowledgePackFixturePaths.packRoot()
            .resolve("skills/" + owner + "/SKILL.md")) || Files.isRegularFile(
                CipKnowledgePackFixturePaths.addonRoot().resolve("skills/" + owner + ".json")), owner);
      }
      String descriptorPath = row.path("descriptorPath").asText();
      assertFalse(descriptorPath.isBlank(), "Runtime descriptor path is absent for " + ruleId);
      boolean unsupportedTopology =
          contract.topology().containsKey(ruleId) && !contract.topology().get(ruleId).supported();
      if ("unsupported".equals(descriptorPath)) {
        assertTrue(
            unsupportedTopology,
            "Supported rule " + ruleId + " cannot map to an unsupported descriptor");
      } else {
        Path descriptorFile = descriptorFileFromPath(descriptorPath);
        assertTrue(
            Files.isRegularFile(descriptorFile),
            "Runtime descriptor is missing for " + ruleId + ": " + descriptorFile);
      }
    }

    assertFalse(contract.topology().get("choice").supported());
    assertFalse(contract.topology().get("generic-barrier").supported());
    assertFalse(contract.topology().get("generic-aggregate").supported());

    String generatorContracts = readPackKnowledge("GENERATOR_CONTRACTS.md");
    String validationRules = readPackKnowledge("validation-rules.yaml");
    String ruleMapping = readPackKnowledge("generator-rule-mapping.md");
    assertFalse(generatorContracts.contains("choice/when/otherwise (for 3+ branches)"));
    assertFalse(validationRules.contains("async_split_element_2_count < 2"));
    assertTrue(validationRules.contains("async_split_element_2_count < 1"));
    assertFalse(ruleMapping.contains("| R-205 | Trigger Count Limit |"));
    assertTrue(
        ruleMapping
            .lines()
            .anyMatch(line -> line.contains("| R-205 |") && line.contains("VR-G-001")),
        "R-205 must map to VR-G-001");
    assertTrue(ruleMapping.contains("VR-G-019"));
    assertTrue(ruleMapping.contains("element_count"));
    assertFalse(generatorContracts.contains(">= 1 branches"));
    assertTrue(generatorContracts.contains("1 or more branches"));
    assertFalse(hasCompilerSourceChoice(generatorContracts));
    assertFalse(hasCompilerSourceChoice(validationRules));
    assertFalse(hasCompilerSourceChoice(ruleMapping));
  }

  private static ClasspathCipKnowledgePackRepository classpathRepository() {
    return new ClasspathCipKnowledgePackRepository(CipKnowledgePackFixturePaths.packVersion());
  }

  private static RuntimeElementDescriptor runtimeDescriptor(String elementType) throws Exception {
    Path yaml = elementDescriptorPath(elementType);
    RuntimeElementDescriptor descriptor =
        new ObjectMapper(new YAMLFactory()).readValue(yaml.toFile(), RuntimeElementDescriptor.class);
    assertNotNull(descriptor, "Runtime descriptor is missing: " + elementType);
    return descriptor;
  }

  private static Path elementDescriptorPath(String elementType) {
    Path dir = resolveRepoPath("integration-build-pipeline/src/main/resources/elements/" + elementType);
    Path yaml = dir.resolve("description.yaml");
    if (Files.isRegularFile(yaml)) {
      return yaml;
    }
    Path yml = dir.resolve("description.yml");
    if (Files.isRegularFile(yml)) {
      return yml;
    }
    throw new IllegalStateException("Runtime descriptor is missing for " + elementType);
  }

  private static String readPackSkill(String skillId) throws Exception {
    return Files.readString(CipKnowledgePackFixturePaths.packRoot().resolve("skills/" + skillId + "/SKILL.md"));
  }

  private static String readPackKnowledge(String fileName) throws Exception {
    return Files.readString(CipKnowledgePackFixturePaths.knowledgeRoot().resolve("ai").resolve(fileName));
  }

  private static Path packKnowledgeFile(String fragment) {
    String fileName =
        switch (fragment) {
          case "validation-rules" -> "validation-rules.yaml";
          case "generator-contracts" -> "GENERATOR_CONTRACTS.md";
          case "generator-rule-mapping" -> "generator-rule-mapping.md";
          default -> fragment;
        };
    return CipKnowledgePackFixturePaths.knowledgeRoot().resolve("ai").resolve(fileName);
  }

  private static Path descriptorFileFromPath(String descriptorPath) {
    int fragment = descriptorPath.indexOf('#');
    String filePath = fragment >= 0 ? descriptorPath.substring(0, fragment) : descriptorPath;
    // The pinned skill pack still cites paths from before the module moves.
    return resolveRepoPath(filePath
        .replace("runtime-catalog/src/main/resources/elements/",
            "integration-build-pipeline/src/main/resources/elements/")
        .replace("schemas/src/main/resources/qip-model/",
            "schemas/src/main/resources/conf-model/"));
  }

  private static boolean hasCompilerSourceChoice(String text) {
    return text.contains(COMPILER_SOURCE_CHOICE)
        || text.contains("GENERATE, DERIVE, or PROVIDE")
        || text.contains("GENERATE, DERIVE, or NONE");
  }

  private static Path resolveRepoPath(String relative) {
    List<Path> candidates = List.of(Path.of(relative), Path.of("..").resolve(relative));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize().toAbsolutePath();
      if (Files.exists(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("Missing repository path: " + relative);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record RuntimeElementDescriptor(Map<String, CatalogChildQuantity> allowedChildren) {

    int minimumChildren() {
      if (allowedChildren == null || allowedChildren.isEmpty()) {
        return 0;
      }
      return allowedChildren.values().stream().mapToInt(CatalogChildQuantity::minimum).min().orElse(0);
    }
  }
}
