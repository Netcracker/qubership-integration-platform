package org.qubership.integration.platform.ai.prompts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailPromptContractTest {

  @Test
  void guardrailRolePromptDocumentsPlanAppendixBias() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/guardrail.md");
    String text = Files.readString(path);
    assertTrue(text.contains("Current active chain implementation plan"), text);
    assertTrue(text.contains("enumerated answers"), text);
    assertTrue(text.contains("content omitted"), text);
    assertTrue(text.contains("See attached files"), text);
  }
}
