package org.qubership.integration.platform.ai.chain.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;

class ChainContextExtractorTest {

  private static final String CONVERSATION_ID = "conv-chain-context";

  @Test
  void parsesChainIdFromCompactSchemaAttachment() {
    ChainContextExtractor extractor = newExtractor();

    ChatRequest request = new ChatRequest();
    request.setAttachment(
        """
        ## Current Chain: Greetings (ID: chain-42)
        ```json
        {
          "chainId": "chain-42",
          "chainName": "Greetings",
          "elements": [],
          "connections": []
        }
        ```
        """);

    assertEquals("chain-42", extractor.resolveChainId(request, CONVERSATION_ID).orElseThrow());
    assertTrue(extractor.hasChainContext(request, CONVERSATION_ID));
  }

  @Test
  void parsesChainIdFromHeadingWhenJsonMissing() {
    ChainContextExtractor extractor = newExtractor();

    ChatRequest request = new ChatRequest();
    request.setAttachment("## Current Chain: Demo (ID: abc-123-def)\n");

    assertEquals(
        "abc-123-def", extractor.resolveChainId(request, CONVERSATION_ID).orElseThrow());
  }

  private static ChainContextExtractor newExtractor() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    return new ChainContextExtractor(
        new com.fasterxml.jackson.databind.ObjectMapper(),
        new InMemorySkillWorkspaceStore(new ChainPlanStore()));
  }
}
