package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

class DesignPlanCaptureAdapterTest {

  private final DesignPlanCaptureAdapter adapter = new DesignPlanCaptureAdapter();

  @Test
  void ownsReleaseAndKeepsMachineIdentityIndependentOfSummaryWording() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DesignPlanContract first =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Resolve endpoint", "Connect service"),
            revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision),
            "2026.1");
    DesignPlanContract second =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Publish the endpoint", "Anything at all"),
            revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision),
            "2026.1");

    assertEquals("2026.1", first.apiRelease());
    assertEquals(first.contractId(), second.contractId());
    assertNotEquals(first.steps().get(1).summary(), second.steps().get(1).summary());
  }

  @Test
  void rejectsDuplicateNotesBeforeProjection() {
    DesignPlanCapture valid = DesignPlanTestFixtures.validCapture("Trigger", "Call");
    List<DesignPlanCapture.Note> duplicate = new ArrayList<>(valid.notes());
    duplicate.add(valid.notes().getFirst());
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            adapter.adapt(
                new DesignPlanCapture(duplicate),
                revision, DesignPlanTestFixtures.brief(),
                DesignPlanTestFixtures.planningPin(revision),
                "2026.1"));
  }

  @Test
  void emptyNotesStillProduceAllTargetsAndSupportSteps() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DesignPlanContract contract = adapter.adapt(new DesignPlanCapture(List.of()), revision,
        DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision), "2026.1");

    assertTrue(contract.steps().stream().anyMatch(step -> step.claims().stream().anyMatch(claim ->
        claim.targetKind() == TargetKind.ENTRY_POINT && claim.role() == ClaimRole.PRODUCER)));
    assertTrue(contract.steps().stream().anyMatch(step -> step.claims().stream().anyMatch(claim ->
        claim.targetKind() == TargetKind.SERVICE_CALL && claim.role() == ClaimRole.PRODUCER)));
    assertEquals("cip-chain-validator", contract.steps().getLast().owner().id());
    assertTrue(new DesignPlanContractValidator().findings(contract, revision,
        DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision)).isEmpty());
  }
}
