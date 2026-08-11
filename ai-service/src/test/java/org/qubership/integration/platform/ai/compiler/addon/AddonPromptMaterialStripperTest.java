package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AddonPromptMaterialStripperTest {

  @Test
  void stripsDenylistedSectionsAndKeepsPromptMaterial() {
    String content =
        """
        # cip-routing-generator addon

        ## Upstream

        - Source: skills/cip-routing-generator/SKILL.md
        - Hash: abc

        ## Runtime contract

        - Capture tool: captureGraphPatch

        ## Applicability in ai-service

        - Audit and repair routing.

        ## ai-service catalog mapping

        - Use condition on if nodes.

        ## Mapping rules

        - Use propertyPatches for condition.

        ## Examples

        - examples/cip-routing-generator/valid-patch-empty.json

        ## Readiness signals

        ```yaml
        readiness:
          mode: ai-service-adapter
        ```

        ## Runtime metadata

        ```yaml
        runtime:
          promoted: true
        ```
        """;

    String stripped = AddonPromptMaterialStripper.stripForPrompt(content);

    assertTrue(stripped.contains("# cip-routing-generator addon"));
    assertTrue(stripped.contains("## Applicability in ai-service"));
    assertTrue(stripped.contains("Audit and repair routing."));
    assertTrue(stripped.contains("## ai-service catalog mapping"));
    assertTrue(stripped.contains("## Mapping rules"));
    assertFalse(stripped.contains("## Upstream"));
    assertFalse(stripped.contains("Hash: abc"));
    assertFalse(stripped.contains("## Runtime contract"));
    assertFalse(stripped.contains("Capture tool: captureGraphPatch"));
    assertFalse(stripped.contains("## Examples"));
    assertFalse(stripped.contains("valid-patch-empty.json"));
    assertFalse(stripped.contains("## Readiness signals"));
    assertFalse(stripped.contains("## Runtime metadata"));
    assertFalse(stripped.contains("promoted: true"));
  }

  @Test
  void stripsOpenQuestionsAndResolvedCaseInsensitively() {
    String content =
        """
        # naming addon

        ## Corporate naming rules

        - Prefer domain names.

        ## open questions

        - Should we rename folders?

        ## RESOLVED

        - Keep plan-graph names only.
        """;

    String stripped = AddonPromptMaterialStripper.stripForPrompt(content);

    assertTrue(stripped.contains("## Corporate naming rules"));
    assertTrue(stripped.contains("Prefer domain names."));
    assertFalse(stripped.contains("open questions"));
    assertFalse(stripped.contains("rename folders"));
    assertFalse(stripped.contains("RESOLVED"));
    assertFalse(stripped.contains("Keep plan-graph names only."));
  }

  @Test
  void keepsUnknownSectionsFailOpen() {
    String content =
        """
        # addon

        ## Upstream

        - drop me

        ## Patch decision tree

        - keep me
        """;

    String stripped = AddonPromptMaterialStripper.stripForPrompt(content);

    assertTrue(stripped.contains("## Patch decision tree"));
    assertTrue(stripped.contains("keep me"));
    assertFalse(stripped.contains("drop me"));
  }

  @Test
  void doesNotStripH3HeadingsWithDenylistNames() {
    String content =
        """
        # addon

        ## Mapping rules

        ### Examples

        - nested example stays

        ## Runtime metadata

        - drop me
        """;

    String stripped = AddonPromptMaterialStripper.stripForPrompt(content);

    assertTrue(stripped.contains("### Examples"));
    assertTrue(stripped.contains("nested example stays"));
    assertFalse(stripped.contains("drop me"));
  }

  @Test
  void returnsEmptyWhenOnlyDenylistedSectionsRemain() {
    String content =
        """
        ## Upstream

        - source

        ## Runtime metadata

        - meta
        """;

    assertEquals("", AddonPromptMaterialStripper.stripForPrompt(content));
  }

  @Test
  void nullAndBlankReturnEmpty() {
    assertEquals("", AddonPromptMaterialStripper.stripForPrompt(null));
    assertEquals("", AddonPromptMaterialStripper.stripForPrompt("   \n"));
  }
}
