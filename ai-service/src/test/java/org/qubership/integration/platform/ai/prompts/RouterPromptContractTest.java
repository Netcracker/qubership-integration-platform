package org.qubership.integration.platform.ai.prompts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterPromptContractTest {

  @Test
  void routerPromptDocumentsTranscriptAwareClassification() throws Exception {
    Path path = Path.of("src/main/resources/prompts/router-system.md");
    String text = Files.readString(path);
    assertTrue(text.contains("Recent conversation"), text);
    assertTrue(text.contains("Current conversation phase"), text);
    assertTrue(text.contains("IMPLEMENT_CHAIN"), text);
    assertTrue(text.contains("IMPORT_SPECIFICATION"), text);
    assertTrue(text.contains("CREATE_CHAIN_PLAN"), text);
    assertTrue(text.contains("Current active chain implementation plan"), text);
    assertTrue(text.contains("Two-step plan lifecycle"), text);
    assertTrue(text.contains("IMPLEMENT_CHAIN") && text.contains("create the chain"), text);
    assertTrue(
        text.contains("take operations from the IDS")
            || text.contains("take / use operations from the IDS"),
        text);
    assertTrue(text.contains("deployed catalog chain"), text);
  }
}
