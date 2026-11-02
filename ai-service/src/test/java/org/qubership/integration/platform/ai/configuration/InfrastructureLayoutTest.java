package org.qubership.integration.platform.ai.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InfrastructureLayoutTest {
  private static final Path INFRASTRUCTURE = Path.of("../infrastructure").toAbsolutePath().normalize();
  private static final Path COMPOSE_DIRECTORY = INFRASTRUCTURE.resolve("docker");
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @ParameterizedTest
  @ValueSource(strings = {"docker-compose.yml", "docker-compose.a2a-lab.yml", "docker-compose.local-env.yml"})
  void composePathsResolveFromTheBaseComposeDirectory(String fileName) throws Exception {
    JsonNode services = YAML.readTree(COMPOSE_DIRECTORY.resolve(fileName).toFile()).path("services");
    for (JsonNode service : services) {
      JsonNode build = service.path("build");
      if (build.isObject()) {
        Path context = COMPOSE_DIRECTORY.resolve(build.path("context").asText()).normalize();
        assertTrue(Files.isDirectory(context), "Missing build context: " + context);
        assertTrue(Files.isRegularFile(context.resolve(build.path("dockerfile").asText("Dockerfile"))));
      }
      JsonNode envFiles = service.path("env_file");
      if (envFiles.isTextual()) {
        assertTrue(Files.isRegularFile(COMPOSE_DIRECTORY.resolve(envFiles.asText())));
      } else {
        for (JsonNode entry : envFiles) {
          String path = entry.isTextual() ? entry.asText() : entry.path("path").asText();
          Path resolved = COMPOSE_DIRECTORY.resolve(path).normalize();
          if (resolved.getFileName().toString().equals(".env.local")) {
            assertEquals(INFRASTRUCTURE.resolve(".env.local"), resolved);
          } else {
            assertTrue(Files.isRegularFile(resolved), "Missing env file: " + resolved);
          }
        }
      }
    }
  }

  @Test
  void knowledgeVolumeDefaultsToTheAiServiceBuildOutput() throws Exception {
    JsonNode compose = YAML.readTree(COMPOSE_DIRECTORY.resolve("docker-compose.yml").toFile());
    String volume = compose.path("services").path("qip-knowledge-sidecar").path("volumes").get(0).asText();
    String defaultPath = volume.substring(volume.indexOf(":-") + 2, volume.indexOf('}'));
    assertEquals(INFRASTRUCTURE.getParent().resolve("ai-service/target/knowledge-runtime"),
        COMPOSE_DIRECTORY.resolve(defaultPath).normalize());
  }

  @Test
  void umbrellaChartContainsEveryDeclaredDependency() throws Exception {
    Path chart = INFRASTRUCTURE.resolve("helm/cip-dev");
    JsonNode dependencies = YAML.readTree(chart.resolve("Chart.yaml").toFile()).path("dependencies");
    assertTrue(dependencies.size() > 0);
    for (JsonNode dependency : dependencies) {
      Path child = chart.resolve("charts").resolve(dependency.path("name").asText()).resolve("Chart.yaml");
      assertTrue(Files.isRegularFile(child), "Missing chart dependency: " + child);
    }
  }
}
