package org.qubership.integration.platform.ai.chat.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EvidenceIdsTest {

  @Test
  void stripRemovesPipelineAndSkillPrefixes() {
    assertEquals("compile", EvidenceIds.strip("pipeline:compile"));
    assertEquals("cip-trigger-generator", EvidenceIds.strip("skill:cip-trigger-generator"));
    assertEquals("compile", EvidenceIds.strip("compile"));
  }

  @Test
  void wireHelpersAddPrefixes() {
    assertEquals("pipeline:compile", EvidenceIds.wirePipeline("compile"));
    assertEquals("skill:cip-x", EvidenceIds.wireSkill("cip-x"));
  }
}
