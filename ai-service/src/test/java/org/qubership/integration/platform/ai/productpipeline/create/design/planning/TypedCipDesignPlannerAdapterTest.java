package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Owner;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Step;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;

class TypedCipDesignPlannerAdapterTest {

  @Test
  void retriesOneTypedCaptureWithStructuredFindings() {
    List<Optional<String>> failures = new ArrayList<>();
    DesignPlanContract accepted = contract();
    DesignPlanSkillRunner runner =
        (conversationId,
            input,
            formatFailure,
            repairEvidence,
            pinnedSkillHash,
            apiRelease,
            revision,
            brief,
            pin) -> {
          failures.add(formatFailure);
          return failures.size() == 1
              ? new DesignPlanSkillRunner.Result(null, "prose", "MISSING_TARGET_PRODUCER call-1")
              : new DesignPlanSkillRunner.Result(accepted, "captured", null);
        };

    TypedCipDesignPlannerAdapter.Result result =
        new TypedCipDesignPlannerAdapter(runner)
            .plan(
                new PlannerRequest("conversation", "input", "hash", ""),
                "2024.4",
                null,
                null,
                null);

    assertEquals(accepted, result.contract());
    assertEquals(Optional.empty(), failures.getFirst());
    assertTrue(failures.getLast().orElseThrow().contains("MISSING_TARGET_PRODUCER"));
  }

  @Test
  void stopsAfterOneFailedCorrection() {
    DesignPlanContractFinding finding =
        new DesignPlanContractFinding(
            DesignPlanContractFinding.Code.UNKNOWN_TARGET,
            TargetKind.SERVICE_CALL,
            "ghost",
            "step",
            true,
            "The model may phrase this message differently");
    DesignPlanSkillRunner runner =
        (conversationId,
            input,
            formatFailure,
            repairEvidence,
            pinnedSkillHash,
            apiRelease,
            revision,
            brief,
            pin) ->
                new DesignPlanSkillRunner.Result(
                    null, "prose", "UNKNOWN_TARGET ghost", List.of(finding));

    PlannerContractException failure =
        assertThrows(
            PlannerContractException.class,
            () ->
                new TypedCipDesignPlannerAdapter(runner)
                    .plan(
                        new PlannerRequest("conversation", "input", "hash", ""),
                        "2024.4",
                        null,
                        null,
                        null));

    assertTrue(failure.getMessage().contains("after one correction"));
    assertTrue(failure.getMessage().contains("UNKNOWN_TARGET"));
    assertEquals(
        "UNKNOWN_TARGET:SERVICE_CALL:ghost:step",
        failure.recoveryCause().findings().getFirst().message());
  }

  private static DesignPlanContract contract() {
    return new DesignPlanContract(
        "design-plan-contract/v1",
        "contract",
        "revision",
        "hash",
        "2024.4",
        List.of(
            new Step(
                "step",
                "Create",
                new Owner(OwnerKind.SKILL, "cip-trigger-generator"),
                List.of(),
                List.of())));
  }
}
