package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

class ImplementCapabilityLadderTest {

  @Test
  void missingBundleWithoutDraftGathersRequirements() {
    assertEquals(
        ScenarioType.GATHER_REQUIREMENTS,
        ImplementCapabilityLadder.advance(false, false, false));
  }

  @Test
  void missingBundleWithReadyDraftCreatesPlan() {
    assertEquals(
        ScenarioType.CREATE_CHAIN_PLAN,
        ImplementCapabilityLadder.advance(false, true, false));
  }

  @Test
  void bundleWithoutImplementGateCreatesPlan() {
    assertEquals(
        ScenarioType.CREATE_CHAIN_PLAN,
        ImplementCapabilityLadder.advance(true, true, false));
  }

  @Test
  void bundleWithImplementGateImplements() {
    assertEquals(
        ScenarioType.IMPLEMENT_CHAIN,
        ImplementCapabilityLadder.advance(true, true, true));
  }

  @Test
  void guidanceForDemotion() {
    assertEquals(
        ImplementCapabilityLadder.NO_READY_DRAFT_MESSAGE,
        ImplementCapabilityLadder.guidanceForDemotion(ScenarioType.GATHER_REQUIREMENTS));
    assertEquals(
        ImplementCapabilityLadder.NO_APPROVED_PLAN_MESSAGE,
        ImplementCapabilityLadder.guidanceForDemotion(ScenarioType.CREATE_CHAIN_PLAN));
    assertNull(ImplementCapabilityLadder.guidanceForDemotion(ScenarioType.IMPLEMENT_CHAIN));
  }
}
