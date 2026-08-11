package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;

class GatherRequirementsAgentTest {

  @Test
  void registersGatherTools() {
    RegisterAiService annotation = GatherRequirementsAgent.class.getAnnotation(RegisterAiService.class);
    Class<?>[] tools = annotation.tools();

    assertTrue(Arrays.asList(tools).contains(RequirementDraftTool.class));
    assertTrue(Arrays.asList(tools).contains(CatalogSystemTools.class));
    assertTrue(Arrays.asList(tools).contains(ApiHubMcpTools.class));
    assertFalse(
        Arrays.stream(tools)
            .anyMatch(tool -> tool.getSimpleName().equals("QipKnowledgeTools")));
    assertTrue(annotation.maxSequentialToolInvocations() >= 6);
  }
}
