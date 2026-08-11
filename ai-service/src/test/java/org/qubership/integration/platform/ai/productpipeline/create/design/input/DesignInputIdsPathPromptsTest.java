package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignMode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class DesignInputIdsPathPromptsTest {

  @Test
  void stageApprovalTokensDoNotResolveToIdsPath() {
    assertEquals(null, DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("Agree"));
    assertEquals(null, DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("approve"));
    DesignInputIdsPathPrompts prompts =
        DesignInputIdsPathPrompts.withFixedPrompts(ref -> "choice", edges -> "map");
    assertEquals(null, prompts.resolveIdsPathChoice("Agree"));
  }

  @Test
  void keywordRoutingResolvesGenerateAndDeriveWithoutLocaleTables() {
    assertEquals(
        DesignMode.GENERATE,
        DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("Generate full IDS"));
    assertEquals(
        DesignMode.GENERATE, DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("yes"));
    assertEquals(
        DesignMode.DERIVE,
        DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("Derive minimal IDS"));
    assertEquals(DesignMode.DERIVE, DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("no"));
  }

  @Test
  void fallbackIdsPathChoiceIsNonBlankEnglishWithoutLocaleSwitch() {
    DesignInputIdsPathPrompts prompts = new DesignInputIdsPathPrompts();
    RequirementBrief brief =
        new RequirementBrief(
            "Orders", List.of(), List.of(), List.of(), List.of(), "Create order", null, "", List.of());
    String prompt = prompts.idsPathChoicePrompt(brief);
    assertNotNull(prompt);
    assertFalse(prompt.isBlank());
    assertTrue(prompt.contains("integration design document"), prompt);
  }

  /**
   * The question asks for a document; it does not describe what happens without one.
   *
   * <p>Declining produces a derived document the reader never sees, so naming it turns a plain
   * yes-or-no into a comparison between two things, only one of which is ever shown.
   */
  @Test
  void idsPathChoiceDoesNotDescribeTheDeclinedPath() {
    DesignInputIdsPathPrompts prompts = new DesignInputIdsPathPrompts();
    RequirementBrief brief =
        new RequirementBrief(
            "Orders", List.of(), List.of(), List.of(), List.of(), "Create order", null, "", List.of());
    String prompt = prompts.idsPathChoicePrompt(brief).toLowerCase(java.util.Locale.ROOT);

    for (String leaked : List.of("minimal", "derive", "derived", "partial", "shortened")) {
      assertFalse(prompt.contains(leaked), "prompt mentions the internal shortcut: " + prompt);
    }
  }

  @Test
  void mappingGapFallbackListsEdgesAndPassThroughAction() {
    DesignInputIdsPathPrompts prompts = new DesignInputIdsPathPrompts();
    String prompt =
        prompts.mappingGapPrompt(
            null,
            DesignMode.GENERATE,
            List.of("INITIALIZATION mapping required: trigger-1 → call-1"));
    assertTrue(prompt.contains("PASS_THROUGH"), prompt);
    assertTrue(prompt.contains("trigger-1"), prompt);
  }

  @Test
  void llmStubAuthorsChoicePromptWithoutFixedLocaleCatalog() {
    DesignInputIdsPathPrompts prompts =
        DesignInputIdsPathPrompts.withFixedPrompts(
            ref -> "LLM authored IDS choice for: " + ref,
            edges -> "LLM authored mapping ask: " + edges);
    String prompt =
        prompts.idsPathChoicePrompt(
            new RequirementBrief(
                "Orders",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Create order",
                null,
                "",
                List.of()));
    assertTrue(prompt.startsWith("LLM authored IDS choice"), prompt);
  }
}
