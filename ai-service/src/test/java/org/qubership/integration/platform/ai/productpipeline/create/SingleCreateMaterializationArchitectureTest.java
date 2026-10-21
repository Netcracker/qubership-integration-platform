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
 * Guards CREATE hard cutover: legacy implementation pipeline and storage types must not remain in
 * production sources, while product materialization remains present.
 */
class SingleCreateMaterializationArchitectureTest {

  private static final List<String> FORBIDDEN =
      List.of(
          "ImplementChainPipelineRunner",
          "ChainAssemblerSkillExecutor",
          "PipelineStateRepository",
          "GeneratedChainBundleStore",
          "PublicationReceiptStore");

  private static final List<String> REQUIRED =
      List.of("MaterializationCapability", "ProductChainMaterializer", "completePhase6");

  @Test
  void productionSourcesUseProductMaterializationOnly() throws IOException {
    Path root = Path.of("src/main/java");
    List<String> violations = new ArrayList<>();
    scanForbidden(root, violations);

    String allText = readAllJava(root);
    for (String required : REQUIRED) {
      if (!allText.contains(required)) {
        violations.add("missing required type token: " + required);
      }
    }

    if (!violations.isEmpty()) {
      fail("Legacy implementation/storage references remain:\n" + String.join("\n", violations));
    }
    assertTrue(violations.isEmpty());
  }

  private static void scanForbidden(Path root, List<String> violations) throws IOException {
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
                  for (String token : FORBIDDEN) {
                    if (text.contains(token)) {
                      violations.add(path + " references " + token);
                    }
                  }
                } catch (IOException e) {
                  violations.add(path + " unreadable: " + e.getMessage());
                }
              });
    }
  }

  private static String readAllJava(Path root) throws IOException {
    StringBuilder out = new StringBuilder();
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  out.append(Files.readString(path)).append('\n');
                } catch (IOException ignored) {
                  // reported via scanForbidden when relevant
                }
              });
    }
    return out.toString();
  }
}
