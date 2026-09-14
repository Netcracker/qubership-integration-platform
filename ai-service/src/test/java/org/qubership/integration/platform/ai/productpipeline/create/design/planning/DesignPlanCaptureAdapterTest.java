package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;

class DesignPlanCaptureAdapterTest {

  private final DesignPlanCaptureAdapter adapter = new DesignPlanCaptureAdapter();

  @Test
  void ownsReleaseAndKeepsMachineIdentityIndependentOfSummaryWording() {
    DesignPlanContract first =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Resolve endpoint", "Connect service"),
            "revision-1",
            "revision-hash",
            "2026.1");
    DesignPlanContract second =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Publish the endpoint", "Anything at all"),
            "revision-1",
            "revision-hash",
            "2026.1");

    assertEquals("2026.1", first.apiRelease());
    assertEquals(first.contractId(), second.contractId());
    assertNotEquals(first.steps().getFirst().summary(), second.steps().getFirst().summary());
  }

  @Test
  void rejectsDuplicateStepIdsBeforeProjection() {
    DesignPlanCapture valid = DesignPlanTestFixtures.validCapture("Trigger", "Call");
    List<DesignPlanCapture.Step> duplicate = new ArrayList<>(valid.steps());
    duplicate.add(valid.steps().getFirst());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.adapt(
                new DesignPlanCapture(duplicate),
                "revision-1",
                "revision-hash",
                "2026.1"));
  }
}
