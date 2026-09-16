package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.productpipeline.knowledge.RequirementDiscoveryKnowledgeTool;

class GatherRequirementsAgentTest {

  @Test
  void registersGatherTools() {
    RegisterAiService annotation = GatherRequirementsAgent.class.getAnnotation(RegisterAiService.class);
    Class<?>[] tools = annotation.tools();

    assertTrue(Arrays.asList(tools).contains(RequirementDraftTool.class));
    assertTrue(Arrays.asList(tools).contains(CatalogSystemTools.class));
    assertTrue(Arrays.asList(tools).contains(CatalogFirstApiHubDiscoveryTool.class));
    assertTrue(Arrays.asList(tools).contains(RequirementDiscoveryKnowledgeTool.class));
    assertFalse(Arrays.asList(tools).contains(ApiHubMcpTools.class));
    assertTrue(annotation.maxSequentialToolInvocations() >= 9);
  }
}
