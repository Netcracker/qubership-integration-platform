package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.langchain4j.RegisterAiService;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;

class CreateChainPlanAgentContractTest {

  @Test
  void graphConstructionRoleSelectsTheCaptureNamedByThePipeline() throws Exception {
    String role =
        new String(
            Objects.requireNonNull(
                    getClass().getResourceAsStream("/prompts/roles/structure-generator.md"))
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertTrue(role.contains("For `cip-chain-generator`, call\n  **captureChainPlan**"));
    assertTrue(role.contains("For `cip-structure-generator`, call **captureChainStructure**"));
    assertTrue(role.contains("Never substitute one\n  graph capture tool for another"));
  }

  @Test
  void graphConstructionAgentDoesNotExposeApiHubTools() {
    RegisterAiService registration = CreateChainPlanAgent.class.getAnnotation(RegisterAiService.class);

    assertFalse(java.util.List.of(registration.tools()).contains(ApiHubMcpTools.class));
  }
}
