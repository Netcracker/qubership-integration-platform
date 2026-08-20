package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;

class DesignGeneratorSkillAgentTest {

  @Test
  void doesNotExposeApiHubToolsAfterBindingResolution() {
    RegisterAiService registration = DesignGeneratorSkillAgent.class.getAnnotation(RegisterAiService.class);

    assertFalse(Arrays.asList(registration.tools()).contains(ApiHubMcpTools.class));
  }
}
