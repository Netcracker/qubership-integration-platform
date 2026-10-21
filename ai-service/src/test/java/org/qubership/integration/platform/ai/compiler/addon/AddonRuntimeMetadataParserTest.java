package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicyParseException;

class AddonRuntimeMetadataParserTest {

  private final AddonRuntimeMetadataParser parser = new AddonRuntimeMetadataParser();

  @Test
  void parsesPromotedRuntimeMetadata() {
    String content =
        """
        # addon

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: runtime
          runtime-skill: true
          capture:
            tool: captureGraphPatch
        ```
        """;

    AddonRuntimeMetadata metadata = parser.parseAddonContent(content, "test.addon.md");

    assertNotNull(metadata);
    assertTrue(metadata.promoted());
    assertEquals("runtime", metadata.category());
    assertTrue(metadata.runtimeSkill());
    assertEquals(CaptureTool.CAPTURE_GRAPH_PATCH, metadata.captureTool());
    assertTrue(metadata.inputArtifacts().isEmpty());
    assertTrue(metadata.outputArtifacts().isEmpty());
  }

  @Test
  void parsesRuntimeContractInputAndOutputArtifacts() {
    String content =
        """
        # addon

        ## Runtime contract

        - Input artifacts: `RAW_USER_REQUEST`, `REQUIREMENT_BRIEF`
        - Capture tool: `captureSelectedPattern`
        - Output artifacts: `SELECTED_PATTERN`, `ELEMENT_SKELETON`

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: runtime
          runtime-skill: true
          capture:
            tool: captureSelectedPattern
        ```
        """;

    AddonRuntimeMetadata metadata = parser.parseAddonContent(content, "test.addon.md");

    assertNotNull(metadata);
    assertEquals(java.util.List.of("RAW_USER_REQUEST", "REQUIREMENT_BRIEF"), metadata.inputArtifacts());
    assertEquals(
        java.util.List.of("SELECTED_PATTERN", "ELEMENT_SKELETON"), metadata.outputArtifacts());
  }

  @Test
  void returnsNullWhenSectionMissing() {
    assertNull(parser.parseAddonContent("# addon\n", "test.addon.md"));
  }

  @Test
  void rejectsUnsupportedCaptureTool() {
    String content =
        """
        # addon

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
          category: runtime
          capture:
            tool: missingTool
        ```
        """;

    assertThrows(
        CompilerGeneratorPolicyParseException.class,
        () -> parser.parseAddonContent(content, "test.addon.md"));
  }
}
