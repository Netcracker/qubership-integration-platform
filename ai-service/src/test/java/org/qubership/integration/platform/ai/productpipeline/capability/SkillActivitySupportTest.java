package org.qubership.integration.platform.ai.productpipeline.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.junit.jupiter.api.Test;

class SkillActivitySupportTest {

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
}
