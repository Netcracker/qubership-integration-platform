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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
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

    List<String> addons = readRequiredAddonTexts();
    assertTrue(
        addons.stream().noneMatch(text -> text.contains(IDS_COMPILER_EXTRACTION)),
        "Addons must not treat IDS markdown as compiler input");
    assertTrue(
        addons.stream().noneMatch(CompilerContractKnowledgeAlignmentTest::hasCompilerSourceChoice),
        "Deprecated GENERATE/DERIVE/PROVIDE compiler-source choice must be absent");

    assertEquals(contract.sha256(), classpathRepository().requireCompilerContractDigest());
    assertFalse(index.addonSha256().isEmpty());
    assertTrue(index.addonSha256().containsKey("cip-design-executor"));
    assertTrue(index.addonSha256().containsKey("cip-structure-generator"));
  }

  @Test
  void everyCompilerContractRuleMapsToAddonAndDescriptor() throws Exception {
    CompilerContract contract =
        new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    String executorAddon = readAddon("cip-design-executor");
    String structureAddon = readAddon("cip-structure-generator");
    String generatorAddon = readAddon("cip-design-generator");
    String combined = executorAddon + "\n" + structureAddon + "\n" + generatorAddon;

    for (String ruleId : contract.topology().keySet()) {
      assertTrue(
          combined.contains(ruleId),
          "Unmapped compiler contract topology rule: " + ruleId);
    }
    for (String elementType : contract.elements().keySet()) {
      assertTrue(
          combined.contains(elementType),
          "Unmapped compiler contract element: " + elementType);
    }
    for (String addonId : contract.requiredAddons()) {
      assertTrue(
          Files.isRegularFile(addonFile(addonId)),
          "Required addon is missing: " + addonId);
    }
    Path knowledgeAi = QipKnowledgePackFixturePaths.packRoot().resolve("knowledge/ai");
    for (String fragment : contract.requiredKnowledgeFragments()) {
      assertTrue(
          knowledgeFragmentExists(knowledgeAi, fragment),
          "Required knowledge fragment is missing: " + fragment);
    }

    assertFalse(contract.topology().get("choice").supported());
    assertFalse(contract.topology().get("generic-barrier").supported());
    assertFalse(contract.topology().get("generic-aggregate").supported());
    assertTrue(generatorAddon.toLowerCase(Locale.ROOT).contains("derived"));
    assertTrue(structureAddon.toLowerCase(Locale.ROOT).contains("semantic compiler"));
    assertTrue(executorAddon.contains("semantic node"));
    assertTrue(executorAddon.contains("execution edge"));

    String generatorContracts = readKnowledgeFragment("GENERATOR_CONTRACTS.md");
    String validationRules = readKnowledgeFragment("validation-rules.yaml");
    String ruleMapping = readKnowledgeFragment("generator-rule-mapping.md");
    assertFalse(generatorContracts.contains("choice/when/otherwise (for 3+ branches)"));
    assertFalse(validationRules.contains("async_split_element_2_count < 2"));
    assertTrue(validationRules.contains("async_split_element_2_count < 1"));
    assertFalse(ruleMapping.contains("| R-205 | Trigger Count Limit |"));
    assertTrue(ruleMapping.contains("VR-G-019"));
    assertTrue(ruleMapping.contains("element_count"));
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

  private static List<String> readRequiredAddonTexts() throws Exception {
    List<String> texts = new ArrayList<>();
    texts.add(readAddon("cip-design-generator"));
    texts.add(readAddon("cip-structure-generator"));
    texts.add(readAddon("cip-design-executor"));
    Path skillsDir = QipKnowledgePackFixturePaths.addonRoot().resolve("skills");
    try (Stream<Path> stream = Files.list(skillsDir)) {
      for (Path file :
          stream
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".addon.md"))
              .toList()) {
        texts.add(Files.readString(file));
      }
    }
    return texts;
  }

  private static String readAddon(String skillId) throws Exception {
    return Files.readString(addonFile(skillId));
  }

  private static Path addonFile(String skillId) {
    return QipKnowledgePackFixturePaths.addonRoot()
        .resolve("skills")
        .resolve(skillId + ".addon.md");
  }

  private static String readKnowledgeFragment(String fileName) throws Exception {
    Path ai = QipKnowledgePackFixturePaths.packRoot().resolve("knowledge/ai").resolve(fileName);
    if (Files.isRegularFile(ai)) {
      return Files.readString(ai);
    }
    Path fixture = Path.of("src/test/resources/qip-knowledge-fixture/ai").resolve(fileName);
    if (Files.isRegularFile(fixture)) {
      return Files.readString(fixture);
    }
    throw new IllegalStateException("Knowledge fragment is missing: " + fileName);
  }

  private static boolean knowledgeFragmentExists(Path knowledgeAi, String fragment) {
    String fileName =
        switch (fragment) {
          case "validation-rules" -> "validation-rules.yaml";
          case "generator-contracts" -> "GENERATOR_CONTRACTS.md";
          case "generator-rule-mapping" -> "generator-rule-mapping.md";
          default -> fragment;
        };
    return Files.isRegularFile(knowledgeAi.resolve(fileName));
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
