package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Final CREATE hard-cutover guard: residual legacy selectors, profiles, scenario vocabulary, and
 * deleted stacks must not remain in production sources or active product E2E.
 */
class CreateHardCutoverArchitectureTest {

  private static final List<String> FORBIDDEN =
      List.of(
          "QIP_CREATE_RUNTIME",
          "qip.ai.create.runtime",
          "CreateRuntimeMode",
          "create-plan",
          "IntegrationDesign",
          "DesignBypassRecord",
          "ScenarioType.CREATE_DESIGN",
          "ScenarioType.ASK_DESIGN",
          "ScenarioType.INTEGRATION_DESIGN",
          "ScenarioType.PLAN_PROPOSAL",
          // Bare INTEGRATION_DESIGN and VALIDATION_REPORT are gone from this list: no legacy
          // artifact kind carries those names any more, while CreateChainPublicArtifactTypes uses
          // both as public A2A artifact types. The legacy scenario values stay guarded above by
          // their qualified ScenarioType.* forms.
          "DESIGN_BYPASS_RECORD",
          "GENERATED_CHAIN_BUNDLE",
          "PUBLICATION_RECEIPT",
          "SkillOrchestratorPlanningSpine",
          "SkillOrchestrator",
          "CompilerPipelineDriftReporter",
          "ImplementChainPipelineRunner",
          "workflows/create-chain.yaml",
          "QIP_CREATE_PRODUCT_PROFILE_ID",
          "QIP_CREATE_PRODUCT_PROFILE_VERSION",
          "run-scenario.sh",
          "phase1-agree-bundle-loop.sh");

  private static final List<String> SELECTABLE_SCENARIO_VOCAB =
      List.of("CREATE_DESIGN", "ASK_DESIGN", "PLAN_PROPOSAL");

  @Test
  void hardCutoverLeavesNoLegacyCreateResidues() throws IOException {
    List<String> violations = new ArrayList<>();

    scanTree(Path.of("src/main/java"), FORBIDDEN, violations, true);
    scanTree(Path.of("src/main/resources"), FORBIDDEN, violations, true);
    scanTree(Path.of("src/main/resources/prompts"), SELECTABLE_SCENARIO_VOCAB, violations, true);
    scanTree(Path.of("src/main/resources/routing"), SELECTABLE_SCENARIO_VOCAB, violations, true);

    Path compose = Path.of("../infrastructure/docker-compose.yml");
    if (Files.isRegularFile(compose)) {
      scanFile(compose, FORBIDDEN, violations);
    }

    Path productE2e = Path.of("e2e/product-pipeline");
    if (!Files.isDirectory(productE2e)) {
      productE2e = Path.of("../e2e/product-pipeline");
    }
    scanTree(productE2e, FORBIDDEN, violations, false);

    // Deleted CREATE-only helpers must not remain on disk.
    for (String helper : List.of("run-scenario.sh", "phase1-agree-bundle-loop.sh")) {
      Path legacy = Path.of("e2e/scripts").resolve(helper);
      if (!Files.exists(legacy)) {
        legacy = Path.of("../e2e/scripts").resolve(helper);
      }
      if (Files.exists(legacy)) {
        violations.add("deleted CREATE-only helper still present: " + legacy);
      }
    }

    if (!violations.isEmpty()) {
      fail("CREATE hard-cutover residues remain:\n" + String.join("\n", violations));
    }
    assertTrue(violations.isEmpty());
  }

  private static void scanTree(
      Path root, List<String> tokens, List<String> violations, boolean javaResources)
      throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> !path.toString().contains("/__pycache__/"))
          .filter(path -> !path.getFileName().toString().endsWith(".pyc"))
          .filter(
              path -> {
                String name = path.getFileName().toString();
                if (javaResources) {
                  return name.endsWith(".java")
                      || name.endsWith(".md")
                      || name.endsWith(".yaml")
                      || name.endsWith(".yml")
                      || name.endsWith(".properties")
                      || name.endsWith(".json");
                }
                return name.endsWith(".sh")
                    || name.endsWith(".py")
                    || name.endsWith(".md")
                    || name.endsWith(".json")
                    || name.endsWith(".yml")
                    || name.endsWith(".yaml");
              })
          .forEach(path -> scanFile(path, tokens, violations));
    }
  }

  private static void scanFile(Path path, List<String> tokens, List<String> violations) {
    String fileName = path.getFileName().toString();
    if (fileName.contains("ArchitectureTest")) {
      return;
    }
    // Task 1 historical binding fixtures intentionally mention legacy pins.
    if (fileName.contains("CreateRunBindingStoreTest")
        || fileName.contains("CreateChainProductPipelineRestartIT")) {
      return;
    }
    try {
      String text = Files.readString(path);
      for (String token : tokens) {
        if (text.contains(token)) {
          violations.add(path + " contains forbidden token: " + token);
        }
      }
    } catch (IOException e) {
      violations.add(path + " unreadable: " + e.getMessage());
    }
  }
}
