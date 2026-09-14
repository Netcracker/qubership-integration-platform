package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkiverse.langchain4j.RegisterAiService;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanCaptureTool;

class DesignPlanCaptureAgentTest {

  @Test
  void exposesOnlyTheTypedPlanCaptureTool() {
    RegisterAiService registration =
        DesignPlanCaptureAgent.class.getAnnotation(RegisterAiService.class);

    assertNotNull(registration);
    assertArrayEquals(new Class<?>[] {DesignPlanCaptureTool.class}, registration.tools());
    assertEquals(4, registration.maxSequentialToolInvocations());
  }
}
