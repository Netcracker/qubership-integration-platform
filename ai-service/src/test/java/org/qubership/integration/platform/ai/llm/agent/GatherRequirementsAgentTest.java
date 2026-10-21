package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.langchain4j.RegisterAiService;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementCaptureToolProvider;

class GatherRequirementsAgentTest {

  @Test
  void systemPromptKeepsMissingMethodsAndNativeTargetSelectionExplicit() throws Exception {
    String resource = GatherRequirementsAgent.class.getMethod("chat", String.class, String.class,
        dev.langchain4j.invocation.InvocationParameters.class).getAnnotation(dev.langchain4j.service.SystemMessage.class)
        .fromResource();
    try (var stream = GatherRequirementsAgent.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(stream);
      String prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          .replaceAll("\\s+", " ");
      assertTrue(prompt.contains("Leave httpMethod null"));
      assertTrue(prompt.contains("do not guess GET or POST"));
      assertTrue(prompt.contains("targetReference"));
      assertTrue(prompt.contains("chain-trigger element ID only"));
      assertTrue(prompt.contains("remove its answered target question"));
      assertTrue(prompt.contains("complete runtime keys or scalar values"), prompt);
      assertTrue(prompt.contains("complete executable constants"), prompt);
      assertTrue(prompt.contains("literal component or prefix byte-for-byte in exactValues"), prompt);
      assertTrue(prompt.contains("Compose separators and runtime expressions outside"), prompt);
      assertTrue(prompt.contains("object property assignment or binding"), prompt);
      assertTrue(prompt.contains("exact key and any actual fixed scalar separately"), prompt);
      assertTrue(prompt.contains("whole key=value clause is a required constant only"), prompt);
      assertTrue(prompt.contains("explicitly requests that complete string as a literal"), prompt);
      assertTrue(prompt.contains("whole binding, computation, and composition descriptions in BEHAVIOR text"), prompt);
      assertTrue(prompt.contains("Do not list whole assignment descriptions"), prompt);
      assertTrue(prompt.contains("A complete literal such as \"a=b\" remains valid"), prompt);
    }
  }

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
