package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSource;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class LegacyCreateWorkflowArchitectureTest {

  private static final List<String> FORBIDDEN =
      List.of(
          "org.qubership.integration.platform.ai.workflow",
          "CreateChainWorkflowRegistry",
          "DefaultSkillOrchestrator",
          "BuildChainDependencyGraph",
          "CompilerPipelineDriftReporter");

  @Test
  void productionSourcesDoNotReferenceLegacyWorkflowPackage() throws IOException {
    List<String> violations = new ArrayList<>();
    scan(Path.of("src/main"), FORBIDDEN, violations);
    scan(Path.of("src/test"), FORBIDDEN, violations);
    // Precise SkillOrchestrator interface token (avoid SkillOrchestratorScenario false positives).
    scanExact(Path.of("src/main"), "SkillOrchestrator", violations);
    scanExact(Path.of("src/test"), "SkillOrchestrator", violations);
    violations.removeIf(v -> v.contains("LegacyCreateWorkflowArchitectureTest"));
    violations.removeIf(v -> v.contains("LegacyCreateChatArchitectureTest"));
    violations.removeIf(v -> v.contains("CreateHardCutoverArchitectureTest"));
    violations.removeIf(v -> v.contains("SingleCreateMaterializationArchitectureTest"));
    if (!violations.isEmpty()) {
      fail("Legacy workflow references remain:\n" + String.join("\n", violations));
    }
  }

  @Test
  void skillOrchestratorInterfaceIsAbsentAfterDefaultOrchestratorRemoval() {
    assertFalse(
        Files.exists(
            Path.of(
                "src/main/java/org/qubership/integration/platform/ai/skill/orchestration/SkillOrchestrator.java")));
    assertFalse(
        Files.exists(
            Path.of(
                "src/main/java/org/qubership/integration/platform/ai/skill/orchestration/DefaultSkillOrchestrator.java")));
  }

  @Test
  void compilerDerivedPlanningDoesNotReferenceBuildChainDependencyGraph() throws IOException {
    Path spine =
        Path.of(
            "src/main/java/org/qubership/integration/platform/ai/productpipeline/create/CompilerDerivedPlanningSpine.java");
    String text = Files.readString(spine);
    assertFalse(text.contains("BuildChainDependencyGraph"));
  }

  @Test
  void indexSupportGenerationSkillIdsWorksWithoutDriftReporter() {
    CompilerPipelineIndex empty =
        new CompilerPipelineIndex(
            1,
            new QipKnowledgePackVersion("1.0.0", "1.0.0"),
            new CompilerPipelineIndexSource("g", "r"),
            List.of(),
            null,
            Map.of(),
            List.of(),
            List.of());
    assertTrue(CompilerPipelineIndexSupport.generationSkillIds(empty).isEmpty());
  }

  @Test
  void knowledgeIngestionWritesCompilerCompatibilityWithoutServiceWorkflowYaml() {
    assertFalse(Files.exists(Path.of("src/main/resources/workflows/create-chain.yaml")));
  }

  private static void scan(Path root, List<String> tokens, List<String> violations)
      throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
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

  private static void scanExact(Path root, String token, List<String> violations) throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  String text = Files.readString(path);
                  int idx = 0;
                  while ((idx = text.indexOf(token, idx)) >= 0) {
                    char after =
                        idx + token.length() < text.length()
                            ? text.charAt(idx + token.length())
                            : ' ';
                    if (!Character.isJavaIdentifierPart(after)) {
                      violations.add(path + " contains " + token);
                      break;
                    }
                    idx += token.length();
                  }
                } catch (IOException e) {
                  violations.add(path + " unreadable: " + e.getMessage());
                }
              });
    }
  }
}
