package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class ChatEventStepLabelTest {

  @Test
  void skillStepMapsLabelAndKeepsTechnicalId() {
    ChatEvent.Step step = assertInstanceOf(ChatEvent.Step.class, ChatEvent.skillStep("cip-requirement-analyzer", "running"));
    assertEquals("skill:cip-requirement-analyzer", step.id());
    assertEquals("skill", step.kind());
    assertEquals("running", step.status());
    assertEquals("Parsing requirements", step.label());
  }

  @Test
  void toolStepMapsHttpPathAndKeepsTechnicalId() {
    ChatEvent.Step step =
        assertInstanceOf(
            ChatEvent.Step.class,
            ChatEvent.step("tool:POST /v1/systems/search", "tool", "running", "POST /v1/systems/search", "skill:cip-requirement-analyzer"));
    assertEquals("tool:POST /v1/systems/search", step.id());
    assertEquals("Searching for a service", step.label());
    assertEquals("skill:cip-requirement-analyzer", step.parentId());
  }

  @Test
  void pipelineStepDoesNotMap() {
    ChatEvent.Step step =
        assertInstanceOf(
            ChatEvent.Step.class, ChatEvent.step("pipeline:compile", "pipeline", "running", "compile", null));
    assertEquals("compile", step.label());
  }
}
