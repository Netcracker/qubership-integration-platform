package org.qubership.integration.platform.ai.prompts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateDesignPromptContractTest {

  @Test
  void createDesignPromptSplitsApiHubVsCustomInbound() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/create-design.md");
    String text = Files.readString(path);
    assertTrue(text.contains("APIHub vs custom inbound HTTP"), text);
    assertTrue(text.contains("custom/internal HTTP endpoint"), text);
    assertTrue(text.contains("documented external REST APIs"), text);
  }
}
