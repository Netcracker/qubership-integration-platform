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
 * Guards CREATE hard cutover: legacy chat/design types and selectable scenario names must not
 * remain in production sources or router prompts.
 */
class LegacyCreateChatArchitectureTest {

  private static final List<String> FORBIDDEN_TYPE_TOKENS =
      List.of(
          "IntegrationDesignStore",
          "DesignBypassRecordStore",
          "IntegrationDesignScenario",
          "PlanProposalScenario",
          "SkillOrchestratorScenario",
          "GatherRequirementsScenario");

  private static final List<String> FORBIDDEN_SCENARIO_NAMES =
      List.of("CREATE_DESIGN", "ASK_DESIGN", "INTEGRATION_DESIGN", "PLAN_PROPOSAL");

  @Test
  void productionSourcesDoNotReferenceLegacyCreateChatStack() throws IOException {
    Path root = Path.of("src/main/java");
    Path prompts = Path.of("src/main/resources/prompts");
    Path routing = Path.of("src/main/resources/routing");
    List<String> violations = new ArrayList<>();

    scan(root, FORBIDDEN_TYPE_TOKENS, violations);
    scan(prompts, FORBIDDEN_SCENARIO_NAMES, violations);
    scan(prompts, FORBIDDEN_TYPE_TOKENS, violations);
    scan(routing, FORBIDDEN_SCENARIO_NAMES, violations);

    // Heuristics must not emit removed scenario enums.
    Path heuristics =
        Path.of(
            "src/main/java/org/qubership/integration/platform/ai/llm/routing/RouterHeuristics.java");
    String heuristicsText = Files.readString(heuristics);
    for (String name : FORBIDDEN_SCENARIO_NAMES) {
      if (heuristicsText.contains("ScenarioType." + name)) {
        violations.add(heuristics + " references ScenarioType." + name);
      }
    }

    if (!violations.isEmpty()) {
      fail("Legacy CREATE chat/design references remain:\n" + String.join("\n", violations));
    }
    assertTrue(violations.isEmpty());
  }

  private static void scan(Path root, List<String> tokens, List<String> violations)
      throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(
              path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".java")
                    || name.endsWith(".md")
                    || name.endsWith(".yaml")
                    || name.endsWith(".yml");
              })
          .forEach(
              path -> {
                try {
                  String text = Files.readString(path);
                  for (String token : tokens) {
                    if (text.contains(token)) {
                      violations.add(path + " contains " + token);
                    }
                  }
                } catch (IOException e) {
                  violations.add(path + " unreadable: " + e.getMessage());
                }
              });
    }
  }
}
