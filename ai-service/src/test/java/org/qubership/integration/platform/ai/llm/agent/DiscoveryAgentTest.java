package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.plan.RequirementBriefTool;

class DiscoveryAgentTest {

  @Test
  void registersDiscoveryTools() {
    RegisterAiService annotation = DiscoveryAgent.class.getAnnotation(RegisterAiService.class);
    Class<?>[] tools = annotation.tools();

    assertTrue(Arrays.asList(tools).contains(RequirementBriefTool.class));
    assertTrue(Arrays.asList(tools).contains(ApiHubMcpTools.class));
    assertFalse(
        Arrays.stream(tools)
            .anyMatch(tool -> tool.getSimpleName().equals("QipKnowledgeTools")));
  }
}
