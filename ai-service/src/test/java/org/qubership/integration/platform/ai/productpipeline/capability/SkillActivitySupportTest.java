package org.qubership.integration.platform.ai.productpipeline.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

class SkillActivitySupportTest {

  @AfterEach
  void tearDown() {
    ToolInvocationSink.unbind();
  }

  @Test
  void bindParentsEmitsSkillRunningOnTheBoundTurnSink() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add);
    try {
      SkillActivitySupport.bindParents("cip-design-planner");
    } finally {
      SkillActivitySupport.clearParents();
      ToolInvocationSink.unbind();
    }

    assertTrue(
        out.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Step step
                        && "skill".equals(step.kind())
                        && "Planning the implementation".equals(step.label())
                        && "running".equals(step.status())),
        () -> "expected a kind=skill running step from bindParents, got: " + out);
  }

  @Test
  void wrapTerminalPrependsCompletedSkillForCandidateOutcome() {
    CapabilitySignal.Completed completed =
        new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CANDIDATE, "ok"));
    List<CapabilitySignal> wrapped =
        SkillActivitySupport.wrapTerminal("brainstorming", List.of(completed));

    assertEquals(2, wrapped.size());
    CapabilitySignal.SkillProgress skill =
        assertInstanceOf(CapabilitySignal.SkillProgress.class, wrapped.get(0));
    assertEquals("brainstorming", skill.skillId());
    assertEquals("completed", skill.status());
    assertEquals(completed, wrapped.get(1));
  }

  @Test
  void wrapTerminalMarksContractFailureAsSkillError() {
    CapabilitySignal.Completed completed =
        new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "bad"));
    List<CapabilitySignal> wrapped =
        SkillActivitySupport.wrapTerminal("cip-requirement-analyzer", List.of(completed));

    CapabilitySignal.SkillProgress skill =
        assertInstanceOf(CapabilitySignal.SkillProgress.class, wrapped.get(0));
    assertEquals("error", skill.status());
  }

  @Test
  void bindWorkerLooksUpTheTurnSinkByConversationId() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, null, "conv-design-exec");
    try {
      SkillActivitySupport.bindWorker("cip-design-executor", "conv-design-exec");
    } finally {
      SkillActivitySupport.unbindWorker();
      ToolInvocationSink.unbind();
    }

    assertTrue(
        out.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Step step
                        && "skill".equals(step.kind())
                        && "Executing the plan".equals(step.label())
                        && "running".equals(step.status())),
        () -> "expected skill running after bindWorker by conversation id, got: " + out);
  }

  @Test
  void unbindWorkerClearsABindLookedUpByConversationId() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, null, "conv-design-exec");
    SkillActivitySupport.bindWorker("cip-design-executor", "conv-design-exec");
    SkillActivitySupport.unbindWorker();
    ToolInvocationSink.unbind();

    assertTrue(
        ToolInvocationSink.currentEmit().isEmpty(),
        "expected the conversation lookup bind to be undone");
  }
}
