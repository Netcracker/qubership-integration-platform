package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenChainPromptContractTest {

  @Test
  void presentationPromptAllowsConversationEvidenceAndKeepsItUntrusted() throws IOException {
    String prompt = resource("/prompts/roles/chain-presentation.md");

    assertFalse(prompt.contains("Use **only** the facts JSON"));
    assertTrue(prompt.contains("last assistant turn"));
    assertTrue(prompt.contains("NOT_REQUESTED"));
    assertTrue(prompt.contains("untrusted evidence"));
    assertTrue(prompt.contains("same language"));
  }

  @Test
  void plannerPromptSeparatesReadsFromMutations() throws IOException {
    String prompt = resource("/prompts/roles/open-chain-turn-planner.md");

    assertTrue(prompt.contains("Deployment status uses `ASK`"));
    assertTrue(prompt.contains("Snapshot existence or listing uses `ASK`"));
    assertTrue(prompt.contains("Do not infer a mutation from an error message"));
  }

  private static String resource(String path) throws IOException {
    try (var stream = OpenChainPromptContractTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IOException("Missing test resource: " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
