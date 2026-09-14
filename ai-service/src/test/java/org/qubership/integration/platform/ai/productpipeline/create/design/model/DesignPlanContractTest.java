package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Claim;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Owner;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.Step;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;

class DesignPlanContractTest {

  @Test
  void roundTripsEveryClosedEnumValue() throws Exception {
    List<Claim> claims = new ArrayList<>();
    for (TargetKind kind : TargetKind.values()) {
      for (ClaimRole role : ClaimRole.values()) {
        claims.add(new Claim(kind, kind.name().toLowerCase(), role));
      }
    }
    DesignPlanContract contract =
        new DesignPlanContract(
            "design-plan-contract/v1",
            "contract",
            "revision",
            "hash",
            "2026.1",
            List.of(
                new Step(
                    "skill",
                    "Skill step",
                    new Owner(OwnerKind.SKILL, "cip-trigger-generator"),
                    claims,
                    List.of()),
                new Step(
                    "tool",
                    "Tool step",
                    new Owner(
                        OwnerKind.APIHUB_TOOL,
                        "get_rest_api_operations_specification"),
                    List.of(),
                    List.of("skill"))));
    ObjectMapper mapper = new ObjectMapper();

    assertEquals(
        contract,
        mapper.readValue(mapper.writeValueAsBytes(contract), DesignPlanContract.class));
  }

  @Test
  void copiesMutableInputLists() {
    List<Claim> claims = new ArrayList<>();
    claims.add(new Claim(TargetKind.SERVICE_CALL, "call-1", ClaimRole.PRODUCER));
    List<String> dependencies = new ArrayList<>();
    Step step = new Step("call", "Create call", new Owner(OwnerKind.SKILL, "skill"), claims, dependencies);
    List<Step> steps = new ArrayList<>(List.of(step));

    DesignPlanContract contract = new DesignPlanContract("1", "contract", "revision", "hash", "2026.1", steps);
    claims.clear();
    dependencies.add("other");
    steps.clear();

    assertEquals(1, contract.steps().size());
    assertEquals(1, contract.steps().getFirst().claims().size());
    assertEquals(List.of(), contract.steps().getFirst().dependsOnStepIds());
  }

  @Test
  void rejectsEmptySteps() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DesignPlanContract("1", "contract", "revision", "hash", "2026.1", List.of()));
  }

  @Test
  void rejectsBlankCanonicalFields() {
    Step step =
        new Step("step", "Summary", new Owner(OwnerKind.SKILL, "skill"), List.of(), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DesignPlanContract("1", " ", "revision", "hash", "2026.1", List.of(step)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Step(" ", "Summary", step.owner(), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Claim(TargetKind.REGION, " ", ClaimRole.REFERENCE));
  }
}
