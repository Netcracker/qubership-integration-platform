package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

class ChainEditSkillProgressTest {

  /**
   * {@code events} and {@code toolEvents} are two different sinks on purpose. {@code toChat}
   * always emits the skill step itself, since a chain edit compile runs on the Mutiny worker pool
   * without carrying {@link ToolInvocationSink}'s binding across, so its own emission cannot rely
   * on that sink. Binding {@link ToolInvocationSink} to the same sink under test would double the
   * running step with an emission of its own, which is not what this test is about; nesting a tool
   * step under the running skill is, and that only needs {@link ToolInvocationSink} bound to
   * something.
   */
  @Test
  void toChatEmitsSkillStepsAndNestsToolsUnderTheRunningSkill() {
    List<ChatEvent> events = new ArrayList<>();
    List<ChatEvent> toolEvents = new ArrayList<>();
    ToolInvocationSink.bind(toolEvents::add, null);
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
            ChatEvent.skillStep("cip-script-generator", "completed")),
        events);
    assertEquals(
        List.of(
            ChatEvent.step(
                "tool:captureScript", "tool", "running", "captureScript", "skill:cip-script-generator"),
            ChatEvent.step(
                "tool:captureScript",
                "tool",
                "completed",
                "captureScript",
                "skill:cip-script-generator")),
        toolEvents.stream().filter(event -> event instanceof ChatEvent.Step step && "tool".equals(step.kind())).toList());
  }
}
