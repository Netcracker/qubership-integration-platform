package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import java.nio.charset.StandardCharsets;
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

  @Test
  void systemPromptRequiresTheTypedPlanCaptureTool() throws Exception {
    SystemMessage message =
        DesignPlanCaptureAgent.class
            .getMethod("chat", String.class, String.class)
            .getAnnotation(SystemMessage.class);
    assertNotNull(message);
    try (var stream =
        getClass().getResourceAsStream("/" + message.fromResource())) {
      assertNotNull(stream);
      String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

      assertFalse(prompt.contains("Do not call tools"));
      assertTrue(prompt.contains("captureDesignPlan"));
    }
  }
}
