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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class CompilerContractKnowledgeAlignmentTest {

  private static final String IDS_COMPILER_EXTRACTION =
      "parse the IDS into compiler input";
  private static final String COMPILER_SOURCE_CHOICE = "GENERATE/DERIVE/PROVIDE";
  private static final String TRACKED_FIXTURE =
      "ai-service/src/test/resources/qip-knowledge-fixture";

  @Test
  void knowledgeIndexPinsCompilerContractDigestAndAsyncCardinality(@TempDir Path outputDir)
      throws Exception {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    QipKnowledgePackBuildGenerator.generate(
        QipKnowledgePackFixturePaths.packRoot(),
        outputDir,
        QipKnowledgePackFixturePaths.addonRoot());

    QipKnowledgePackRepository repository =
        new FilesystemQipKnowledgePackRepository(
            outputDir, QipKnowledgePackFixturePaths.packVersion());
    QipKnowledgePackManifest index = repository.loadManifest();

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
  void trackedAddonsTreatIdsAsDerivedViewAndReuseSemanticCompilerSeed() throws Exception {
    String generatorAddon = readTrackedAddon("cip-design-generator");
    String structureAddon = readTrackedAddon("cip-structure-generator");
    String executorAddon = readTrackedAddon("cip-design-executor");
    List<String> addons = List.of(generatorAddon, structureAddon, executorAddon);

    assertTrue(
        addons.stream().noneMatch(text -> text.contains(IDS_COMPILER_EXTRACTION)),
        "Addons must not treat IDS markdown as compiler input");
    assertTrue(
        addons.stream().noneMatch(CompilerContractKnowledgeAlignmentTest::hasCompilerSourceChoice),
        "Deprecated GENERATE/DERIVE/PROVIDE compiler-source choice must be absent");
    assertTrue(
        generatorAddon.toLowerCase(Locale.ROOT).contains("derived"),
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
          Files.isRegularFile(trackedAddonFile(addonId)),
          "Required tracked addon is missing: " + addonId);
    }
    for (String fragment : contract.requiredKnowledgeFragments()) {
      assertTrue(
          Files.isRegularFile(trackedKnowledgeFile(fragment)),
          "Required tracked knowledge fragment is missing: " + fragment);
    }
  }

  @Test
  void everyCompilerContractRuleMapsToAddonAndDescriptor() throws Exception {
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    String executorAddon = readTrackedAddon("cip-design-executor");
    String structureAddon = readTrackedAddon("cip-structure-generator");
    String generatorAddon = readTrackedAddon("cip-design-generator");
    Set<String> headings = addonHeadings(generatorAddon, structureAddon, executorAddon);
    Map<String, MappingRow> rows = parseOwnershipTable(executorAddon);

    Set<String> requiredIds = new LinkedHashSet<>();
    requiredIds.addAll(contract.topology().keySet());
    requiredIds.addAll(contract.elements().keySet());
    for (String ruleId : requiredIds) {
      MappingRow row = rows.get(ruleId);
      assertNotNull(row, "Unmapped compiler contract rule: " + ruleId);
      assertFalse(
          row.addonSection().isBlank() || "this mapping".equalsIgnoreCase(row.addonSection()),
          "Named addon section is missing for " + ruleId);
      assertTrue(
          headingCovers(headings, row.addonSection()),
          "Addon section '" + row.addonSection() + "' is not a heading for " + ruleId);
      assertFalse(
          row.descriptorPath().isBlank() || "none".equalsIgnoreCase(row.descriptorPath()),
          "Runtime descriptor path is absent for " + ruleId);
      boolean unsupportedTopology =
          contract.topology().containsKey(ruleId) && !contract.topology().get(ruleId).supported();
      if ("unsupported".equals(row.descriptorPath())) {
        assertTrue(
            unsupportedTopology,
            "Supported rule " + ruleId + " cannot map to an unsupported descriptor");
      } else {
        Path descriptorFile = descriptorFileFromPath(row.descriptorPath());
        assertTrue(
            Files.isRegularFile(descriptorFile),
            "Runtime descriptor is missing for " + ruleId + ": " + descriptorFile);
      }
    }

    assertFalse(contract.topology().get("choice").supported());
    assertFalse(contract.topology().get("generic-barrier").supported());
    assertFalse(contract.topology().get("generic-aggregate").supported());

    String generatorContracts = readTrackedKnowledge("GENERATOR_CONTRACTS.md");
    String validationRules = readTrackedKnowledge("validation-rules.yaml");
    String ruleMapping = readTrackedKnowledge("generator-rule-mapping.md");
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

  private static ClasspathQipKnowledgePackRepository classpathRepository() {
    return new ClasspathQipKnowledgePackRepository(QipKnowledgePackFixturePaths.packVersion());
  }

  private static RuntimeElementDescriptor runtimeDescriptor(String elementType) throws Exception {
    Path yaml = elementDescriptorPath(elementType);
    RuntimeElementDescriptor descriptor =
        new ObjectMapper(new YAMLFactory()).readValue(yaml.toFile(), RuntimeElementDescriptor.class);
    assertNotNull(descriptor, "Runtime descriptor is missing: " + elementType);
    return descriptor;
  }

  private static Path elementDescriptorPath(String elementType) {
    Path dir = resolveRepoPath("runtime-catalog/src/main/resources/elements/" + elementType);
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

  private static String readTrackedAddon(String skillId) throws Exception {
    return Files.readString(trackedAddonFile(skillId));
  }

  private static Path trackedAddonFile(String skillId) {
    return resolveRepoPath(TRACKED_FIXTURE + "/addons/skills").resolve(skillId + ".addon.md");
  }

  private static String readTrackedKnowledge(String fileName) throws Exception {
    return Files.readString(resolveRepoPath(TRACKED_FIXTURE + "/ai").resolve(fileName));
  }

  private static Path trackedKnowledgeFile(String fragment) {
    String fileName =
        switch (fragment) {
          case "validation-rules" -> "validation-rules.yaml";
          case "generator-contracts" -> "GENERATOR_CONTRACTS.md";
          case "generator-rule-mapping" -> "generator-rule-mapping.md";
          default -> fragment;
        };
    return resolveRepoPath(TRACKED_FIXTURE + "/ai").resolve(fileName);
  }

  private static Map<String, MappingRow> parseOwnershipTable(String executorAddon) {
    String marker = "## Semantic node and edge ownership";
    int start = executorAddon.indexOf(marker);
    assertTrue(start >= 0, "Executor addon is missing semantic ownership mapping");
    Map<String, MappingRow> rows = new LinkedHashMap<>();
    boolean inTable = false;
    for (String line : executorAddon.substring(start).split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("|")) {
        if (inTable) {
          break;
        }
        continue;
      }
      List<String> cells = splitTableRow(trimmed);
      if (cells.size() < 4) {
        continue;
      }
      String ruleId = cells.get(0);
      if ("Contract rule".equalsIgnoreCase(ruleId)) {
        inTable = true;
        continue;
      }
      if (ruleId.contains("---")) {
        continue;
      }
      if (!inTable) {
        continue;
      }
      rows.put(ruleId, new MappingRow(ruleId, cells.get(1), cells.get(2), cells.get(3)));
    }
    return rows;
  }

  private static List<String> splitTableRow(String line) {
    String[] raw = line.split("\\|", -1);
    List<String> cells = new ArrayList<>();
    for (int i = 0; i < raw.length; i++) {
      String cell = raw[i].trim();
      if ((i == 0 || i == raw.length - 1) && cell.isEmpty()) {
        continue;
      }
      cells.add(cell);
    }
    return cells;
  }

  private static Set<String> addonHeadings(String... addons) {
    Set<String> headings = new LinkedHashSet<>();
    for (String addon : addons) {
      for (String line : addon.split("\n")) {
        String trimmed = line.trim();
        if (trimmed.startsWith("## ")) {
          headings.add(trimmed.substring(3).trim());
        }
      }
    }
    return headings;
  }

  private static boolean headingCovers(Set<String> headings, String section) {
    for (String heading : headings) {
      if (heading.equals(section) || heading.startsWith(section)) {
        return true;
      }
    }
    return false;
  }

  private static Path descriptorFileFromPath(String descriptorPath) {
    int fragment = descriptorPath.indexOf('#');
    String filePath = fragment >= 0 ? descriptorPath.substring(0, fragment) : descriptorPath;
    return resolveRepoPath(filePath);
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

  private record MappingRow(
      String ruleId, String owner, String addonSection, String descriptorPath) {}

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
