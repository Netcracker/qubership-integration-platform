package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

class ChainEditSkillProgressTest {

  @Test
  void toChatEmitsSkillStepsAndNestsToolsUnderTheRunningSkill() {
    List<ChatEvent> events = new ArrayList<>();
    ToolInvocationSink.bind(events::add, null);
    try {
      var progress = ChainEditSkillProgress.toChat(events::add);
      progress.accept("cip-script-generator", "running");
      ToolInvocationSink.onInvoke("captureScript");
      ToolInvocationSink.onComplete("captureScript");
      progress.accept("cip-script-generator", "completed");
    } finally {
      ToolInvocationSink.unbind();
    }

    assertEquals(
        List.of(
            ChatEvent.skillStep("cip-script-generator", "running"),
            ChatEvent.step(
                "tool:captureScript", "tool", "running", "captureScript", "skill:cip-script-generator"),
            ChatEvent.step(
                "tool:captureScript",
                "tool",
                "completed",
                "captureScript",
                "skill:cip-script-generator"),
            ChatEvent.skillStep("cip-script-generator", "completed")),
        events);
  }
}
