package org.qubership.integration.platform.ai.chat.chainplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainPlanJsonNormalizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void normalizeRootWrapsLegacyNameDescription() throws Exception {
    var root =
        objectMapper.readTree(
            """
            {
              "name": "Petstore Smart Order Gateway",
              "description": "desc",
              "elements": [ { "clientId": "t1", "type": "http-trigger" } ]
            }
            """);
    var normalized = ChainPlanJsonNormalizer.normalizeRoot(objectMapper, root);
    assertTrue(normalized.has("chain"));
    assertEquals("Petstore Smart Order Gateway", normalized.get("chain").get("name").asText());
    assertEquals("desc", normalized.get("chain").get("description").asText());
    assertFalse(normalized.has("name"));
  }

  @Test
  void unwrapMarkdownFencesStripsCodeBlock() {
    String raw =
        """
        ```json
        { "chain": { "name": "X", "description": "" }, "elements": [] }
        ```
        """;
    String unwrapped = ChainPlanJsonNormalizer.unwrapMarkdownFences(raw);
    assertTrue(unwrapped.startsWith("{"));
    assertFalse(unwrapped.contains("```"));
  }
}
