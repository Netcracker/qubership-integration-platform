package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aAgentExecutor;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;

/**
 * Ticket 08: the production browser CREATE path and A2A share {@link
 * CreateChainApplicationFacade}. Transport code must not own lifecycle commands or Flow internals.
 */
class CreateChainSharedFacadeArchitectureTest {

  private static final Path MAIN_JAVA = Path.of("src/main/java");

  private static final List<String> LIFECYCLE_INTERNALS =
      List.of(
          "ProductPipelineStageExecutor",
          "ProvidedIdsFlowTasks",
          "io.quarkiverse.flow",
          "StartOrResumeCommand",
          "AcceptInputCommand",
          "ApproveCommand",
          "ImplementCommand");

  @Test
  void productionBrowserAndA2aShareTheFacadeSeam() throws Exception {
    String coordinator = source(CreateProductPipelineCoordinator.class);
    String adapter = source(ProductPipelineChatAdapter.class);
    String decisions = source(ChatDecisionService.class);
    String a2a = source(CreateChainA2aAgentExecutor.class);
    String facadeName = CreateChainApplicationFacade.class.getSimpleName();

    assertTrue(coordinator.contains(facadeName), "browser coordinator must call the shared facade");
    assertTrue(decisions.contains(facadeName), "typed chat decisions must call the shared facade");
    assertTrue(a2a.contains(facadeName), "A2A executor must call the shared facade");
    assertTrue(
        coordinator.contains("facade.start"),
        "browser start must go through CreateChainApplicationFacade.start");
    assertTrue(
        coordinator.contains("continueWithInput") || coordinator.contains("facade.start"),
        "browser clarification must go through the facade");
    assertTrue(
        coordinator.contains("streamApproveOnly"),
        "browser approval must go through CreateChainApplicationFacade.streamApproveOnly");
    assertTrue(
        coordinator.contains("streamCreateChain"),
        "explicit chain creation must go through CreateChainApplicationFacade.streamCreateChain");

    assertFalse(
        coordinator.contains("StartOrResumeCommand"),
        "browser coordinator must not start the run state machine directly");
    assertFalse(
        coordinator.contains("runtime.approve"),
        "browser coordinator must not approve through the orchestrator");
    assertFalse(
        coordinator.contains("runtime.implement"),
        "browser coordinator must not implement through the orchestrator");
    assertFalse(
        coordinator.contains("runtime.acceptInput"),
        "browser coordinator must not accept input through the orchestrator");
    assertFalse(
        adapter.contains("CreateChainOrchestrator"),
        "chat adapter must not depend on the orchestrator");
    assertFalse(
        a2a.contains("ChatEvent"),
        "A2A must not import browser ChatEvent DTOs");
    assertFalse(
        a2a.contains("UserIntentPatterns") && a2a.contains("GateReplyAgent"),
        "A2A must not apply browser intent rules");
  }

  @Test
  void transportModulesDoNotDependOnStageExecutorOrFlowTasks() throws IOException {
    List<String> violations = new ArrayList<>();
    scanTree(
        MAIN_JAVA.resolve("org/qubership/integration/platform/ai/chat"),
        LIFECYCLE_INTERNALS,
        violations);
    scanTree(
        MAIN_JAVA.resolve("org/qubership/integration/platform/ai/a2a"),
        LIFECYCLE_INTERNALS,
        violations);
    scanFile(
        MAIN_JAVA.resolve(
            "org/qubership/integration/platform/ai/productpipeline/create/CreateProductPipelineCoordinator.java"),
        LIFECYCLE_INTERNALS,
        violations);
    scanFile(
        MAIN_JAVA.resolve(
            "org/qubership/integration/platform/ai/productpipeline/create/ProductPipelineChatAdapter.java"),
        LIFECYCLE_INTERNALS,
        violations);
    if (!violations.isEmpty()) {
      fail("transport/browser CREATE path still owns lifecycle internals:\n"
          + String.join("\n", violations));
    }
  }

  private static String source(Class<?> type) throws IOException {
    return Files.readString(MAIN_JAVA.resolve(type.getName().replace('.', '/') + ".java"));
  }

  private static void scanTree(Path root, List<String> tokens, List<String> violations)
      throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .forEach(path -> scanFile(path, tokens, violations));
    }
  }

  private static void scanFile(Path path, List<String> tokens, List<String> violations) {
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
  }
}
