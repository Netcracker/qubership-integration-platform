package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.llm.agent.DesignInputPromptAgent;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
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
    assertEquals(
        DesignMode.GENERATE,
        DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("yes, write the document"));
    assertEquals(
        DesignMode.DERIVE,
        DesignInputIdsPathPrompts.resolveIdsPathChoiceKeywords("no, carry on without one."));
  }

  /**
   * The gate is named by the run, not recognized in its wording.
   *
   * <p>The question is authored in the language of the conversation, so a card chosen by matching
   * English words disappears the moment a reader answers in another language.
   */
  @Test
  void gateMarkerNamesTheCardWhateverLanguageTheQuestionIsIn() {
    String spanish =
        PipelineGates.tag(
            PipelineGates.IDS_PATH_CHOICE,
            "Quiere un documento de diseno de integracion para estos requisitos?");

    assertEquals(PipelineGates.IDS_PATH_CHOICE, PipelineGates.gateOf(spanish).orElseThrow());
    assertEquals(
        "Quiere un documento de diseno de integracion para estos requisitos?",
        PipelineGates.strip(spanish));
    assertEquals(
        ChatEvent.IDS_PATH_CHOICE_ACTIONS, ChatEvent.actionsForGate(PipelineGates.IDS_PATH_CHOICE));
  }

  @Test
  void anUnmarkedPromptNamesNoGateAndIsAnsweredAsFreeText() {
    assertTrue(PipelineGates.gateOf(DesignInputIdsPathPrompts.FALLBACK_IDS_PATH_CHOICE).isEmpty());
    assertTrue(PipelineGates.gateOf("").isEmpty());
    assertTrue(PipelineGates.gateOf(null).isEmpty());
    assertEquals(null, ChatEvent.actionsForGate(""));
  }

  @Test
  void encodeAndParseMappingGapWaitRoundTrip() {
    List<String> edges =
        List.of(
            "INITIALIZATION: ENDPOINT \"HTTP POST /orders\" → SERVICE_CALL \"Create order\"",
            "RESPONSE: SERVICE_CALL \"Create order\" → ENDPOINT \"HTTP POST /orders\"");
    String encoded =
        DesignInputIdsPathPrompts.encodeMappingGapWait(
            "Some data mappings are still missing before design can continue.", edges);
    assertFalse(encoded.toLowerCase(java.util.Locale.ROOT).contains("reply pass_through"), encoded);
    DesignInputIdsPathPrompts.MappingGapView view =
        DesignInputIdsPathPrompts.parseMappingGapWait(encoded);
    assertEquals("Some data mappings are still missing before design can continue.", view.question());
    assertEquals(edges, view.missingEdges());
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
  void mappingGapFallbackIsShortQuestionWithoutPassThroughInstruction() {
    DesignInputIdsPathPrompts prompts = new DesignInputIdsPathPrompts();
    String prompt =
        prompts.mappingGapPrompt(
            null,
            DesignMode.GENERATE,
            List.of("INITIALIZATION mapping required: trigger-1 → call-1"));
    assertTrue(prompt.toLowerCase(java.util.Locale.ROOT).contains("data mapping"), prompt);
    assertFalse(prompt.contains("Reply PASS_THROUGH"), prompt);
    assertFalse(prompt.contains("trigger-1"), prompt);
  }

  @Test
  void passThroughKeywordRecognizesCardAction() {
    DesignInputIdsPathPrompts prompts = new DesignInputIdsPathPrompts();
    assertTrue(prompts.isPassThroughConfirmation("pass_through"));
    assertTrue(prompts.isPassThroughConfirmation("Pass through"));
    assertFalse(prompts.isPassThroughConfirmation("$.id → $.customerId"));
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

  @Test
  void passesPinnedResponseLocaleToThePromptAgent() {
    AtomicReference<String> receivedLocale = new AtomicReference<>();
    DesignInputPromptAgent agent =
        new DesignInputPromptAgent() {
          @Override
          public String askIdsPathChoice(String responseLocale, String reference) {
            receivedLocale.set(responseLocale);
            return "Question";
          }

          @Override
          public String askMappingGap(
              String responseLocale, String reference, String missingEdges, String pendingMode) {
            return "Mappings";
          }

          @Override
          public String classifyIdsPathChoice(String userText) {
            return "NONE";
          }

          @Override
          public String classifyMappingReply(String userText) {
            return "NONE";
          }
        };
    DesignInputIdsPathPrompts prompts = new DesignInputIdsPathPrompts(agent);

    prompts.idsPathChoicePrompt("en", null, "Create an integration chain");

    assertEquals("en", receivedLocale.get());
  }
}
