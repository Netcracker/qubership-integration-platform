package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.langchain4j.RegisterAiService;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureToolProvider;

class GatherRequirementsAgentTest {

  @Test
  void gatherProviderIsNotAvailableToUnrelatedAgentsThroughDefaultCdiLookup() {
    org.junit.jupiter.api.Assertions.assertFalse(dev.langchain4j.service.tool.ToolProvider.class
        .isAssignableFrom(RequirementCaptureToolProvider.class));
  }

  @Test
  void registersGatherTools() {
    RegisterAiService annotation = GatherRequirementsAgent.class.getAnnotation(RegisterAiService.class);
    Class<?>[] tools = annotation.tools();

    assertTrue(annotation.toolProviderSupplier()
        .equals(RequirementCaptureToolProvider.ProviderSupplier.class));
    assertEquals(0, tools.length);
    assertEquals(RegisterAiService.NoChatMemoryProviderSupplier.class,
        annotation.chatMemoryProviderSupplier());
    assertTrue(annotation.maxSequentialToolInvocations() >= 9);
  }
}
