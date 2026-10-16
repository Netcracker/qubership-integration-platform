package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;

/**
 * Ticket 09: after cutover, production has one Flow-backed create-chain lifecycle. The manual
 * runtime type, rollout flag, fallback producer, and recursive stage loop must be gone.
 */
class CreateChainHardCutoverArchitectureTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");
  private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

  private static final List<String> RETIRED_TOKENS =
      List.of(
          "class ProductPipelineRuntime",
          "qip.ai.create.flow.enabled",
          "QIP_AI_CREATE_FLOW_ENABLED",
          "private Multi<PipelineSignal> advance(",
          "public Multi<PipelineSignal> continueRun(");

  @Test
  void flowBackedOrchestratorIsTheCreateChainOrchestrator() {
    assertTrue(
        CreateChainOrchestrator.class.isAssignableFrom(ProvidedIdsFlowOrchestrator.class),
        "ProvidedIdsFlowOrchestrator must be the Flow-backed CreateChainOrchestrator");
  }

  @Test
  void productionSourcesRejectRetiredRuntimeFlagFallbackAndRecursiveAdvance() throws IOException {
    List<String> violations = new ArrayList<>();
    scanTree(MAIN_JAVA, RETIRED_TOKENS, violations);
    scanTree(MAIN_RESOURCES, RETIRED_TOKENS, violations);
    if (!violations.isEmpty()) {
      fail("retired create-chain lifecycle still present:\n" + String.join("\n", violations));
    }
  }

  @Test
  void transportFacadeAndFlowTasksDoNotCallRetiredRecordOrContinuationMethods() throws IOException {
    List<String> tokens =
        List.of(".recordInput(", ".recordApprove(", ".recordImplement(", ".continueRun(");
    List<String> violations = new ArrayList<>();
    scanTree(
        MAIN_JAVA.resolve("org/qubership/integration/platform/ai/chat"), tokens, violations);
    scanTree(
        MAIN_JAVA.resolve("org/qubership/integration/platform/ai/a2a"), tokens, violations);
    scanFile(
        MAIN_JAVA.resolve(
            "org/qubership/integration/platform/ai/productpipeline/create/CreateProductPipelineCoordinator.java"),
        tokens,
        violations);
    scanFile(
        MAIN_JAVA.resolve(
            "org/qubership/integration/platform/ai/productpipeline/create/ProductPipelineChatAdapter.java"),
        tokens,
        violations);
    scanFile(
        MAIN_JAVA.resolve(
            "org/qubership/integration/platform/ai/productpipeline/create/facade/CreateChainApplicationFacade.java"),
        tokens,
        violations);
    scanFile(
        MAIN_JAVA.resolve(
            "org/qubership/integration/platform/ai/productpipeline/create/flow/ProvidedIdsFlowTasks.java"),
        tokens,
        violations);
    if (!violations.isEmpty()) {
      fail(
          "transport, facade, or Flow tasks still call retired record/continuation methods:\n"
              + String.join("\n", violations));
    }
  }

  @Test
  void producersAlwaysWireTheFlowOrchestratorWithoutAFallbackBranch() throws IOException {
    Path producers =
        MAIN_JAVA.resolve(
            "org/qubership/integration/platform/ai/productpipeline/runtime/ProductPipelineRuntimeProducers.java");
    String text = Files.readString(producers);
    assertFalse(
        text.contains("qip.ai.create.flow.enabled"),
        "producers must not read the Flow rollout flag");
    assertFalse(
        text.contains("flowEnabled"), "producers must not keep a Flow-enabled fallback branch");
    assertTrue(
        text.contains("new ProvidedIdsFlowOrchestrator"),
        "producers must always construct the Flow-backed orchestrator");
    assertFalse(
        text.contains(": runtime"),
        "producers must not fall back to a manual runtime orchestrator");
  }

  private static void scanTree(Path root, List<String> tokens, List<String> violations)
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
                    || name.endsWith(".properties")
                    || name.endsWith(".yml")
                    || name.endsWith(".yaml");
              })
          .forEach(path -> scanFile(path, tokens, violations));
    }
  }

  private static void scanFile(Path path, List<String> tokens, List<String> violations) {
    try {
      String text = Files.readString(path);
      for (String token : tokens) {
        if (containsRetiredToken(text, token)) {
          violations.add(path + " contains " + token);
        }
      }
    } catch (IOException e) {
      violations.add(path + " unreadable: " + e.getMessage());
    }
  }

  private static boolean containsRetiredToken(String text, String token) {
    if ("class ProductPipelineRuntime".equals(token)) {
      return Pattern.compile("\\bclass ProductPipelineRuntime\\b").matcher(text).find();
    }
    return text.contains(token);
  }
}
