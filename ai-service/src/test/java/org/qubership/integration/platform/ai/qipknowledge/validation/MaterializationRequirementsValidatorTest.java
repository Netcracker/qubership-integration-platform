package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonBuildSupport;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.addon.MaterializationRequirementsLoader;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackBuildGenerator;

class MaterializationRequirementsValidatorTest {

  private static final String ADDON_PACK_ROOT_PROPERTY = "qip.ai.qipknowledge.addon-pack-root";

  private MaterializationRequirementsValidator validator;
  private String previousAddonPackRoot;

  @BeforeEach
  void setUp(@TempDir Path outputDir, @TempDir Path addonRoot) throws Exception {
    previousAddonPackRoot = System.getProperty(ADDON_PACK_ROOT_PROPERTY);
    Path fixtureAddonRoot = QipKnowledgePackFixturePaths.addonRoot();
    Files.createDirectories(addonRoot.resolve("global"));
    Files.createDirectories(addonRoot.resolve("skills"));
    try (var globals = Files.newDirectoryStream(fixtureAddonRoot.resolve("global"))) {
      for (Path global : globals) {
        Files.copy(global, addonRoot.resolve("global").resolve(global.getFileName()));
      }
    }
    try (var addons = Files.newDirectoryStream(fixtureAddonRoot.resolve("skills"), "*.addon.md")) {
      for (Path addon : addons) {
        Files.copy(addon, addonRoot.resolve("skills").resolve(addon.getFileName()));
      }
    }
    Files.writeString(
        addonRoot.resolve("global/materialization-requirements.yaml"),
        """
        version: 1
        elementRequirements:
          if:
            ownerGenerator: cip-routing-generator
            requiredProperties:
              - condition
            examples:
              condition: "${exchangeProperty.lang} == 'ru'"
        """);

    System.setProperty(ADDON_PACK_ROOT_PROPERTY, addonRoot.toString());
    QipKnowledgePackBuildGenerator.generate(QipKnowledgePackFixturePaths.packRoot(), outputDir);
    CompilerSkillAddonBuildSupport.materialize(
        addonRoot, outputDir.resolve(QipKnowledgePackFixturePaths.PACK_DIR));

    CompilerSkillAddonRepository addonRepository =
        CompilerSkillAddonRepository.forFilesystem(
            outputDir, QipKnowledgePackFixturePaths.packVersion(), getClass().getClassLoader());
    validator =
        new MaterializationRequirementsValidator(new MaterializationRequirementsLoader(addonRepository));
  }

  @AfterEach
  void restoreAddonPackRoot() {
    if (previousAddonPackRoot == null) {
      System.clearProperty(ADDON_PACK_ROOT_PROPERTY);
    } else {
      System.setProperty(ADDON_PACK_ROOT_PROPERTY, previousAddonPackRoot);
    }
  }

  @Test
  void returnsNoIssuesWhenRequiredPropertyPresent() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(
                new ChainPlanNode(
                    "if-1",
                    "if",
                    "If",
                    null,
                    null,
                    List.of(new PlanProperty("condition", "${lang} == 'ru'")))),
            List.of());

    assertTrue(validator.validate(graph).isEmpty());
  }

  @Test
  void flagsMissingRequiredProperty() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(new ChainPlanNode("if-1", "if", "If", null, null, List.of())),
            List.of());

    var issues = validator.validate(graph);
    assertEquals(1, issues.size());
    assertFalse(issues.get(0).message().isBlank());
    assertEquals("cip-routing-generator", issues.get(0).ownerCapabilityId());
    assertTrue(issues.get(0).message().contains("condition"));
  }
}
