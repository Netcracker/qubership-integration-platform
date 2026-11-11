package org.qubership.integration.platform.ai.chat.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** REST contract: legacy chain-plan endpoint and resource must not remain after cutover. */
class LegacyChainPlanEndpointAbsentTest {

  @Test
  void legacyChainPlanEndpointIsAbsent() throws IOException {
    Path root = Path.of("src/main/java");
    List<String> violations = new ArrayList<>();
    Path resource =
        root.resolve(
            "org/qubership/integration/platform/ai/chat/rest/ChainPlanResource.java");
    if (Files.exists(resource)) {
      violations.add("ChainPlanResource.java still present: " + resource);
    }

    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  String text = Files.readString(path);
                  if (text.contains("ChainPlanResource")
                      || text.contains("@Path(\"/chain-plan")
                      || text.contains("@Path(\"chain-plan")) {
                    violations.add(path + " still references legacy chain-plan endpoint");
                  }
                } catch (IOException e) {
                  violations.add(path + " unreadable: " + e.getMessage());
                }
              });
    }

    if (!violations.isEmpty()) {
      fail("Legacy chain-plan endpoint still present:\n" + String.join("\n", violations));
    }
    assertTrue(violations.isEmpty());
  }
}
