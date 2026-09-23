package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

class DesignPlanReportRendererTest {

  private final DesignPlanCaptureAdapter adapter = new DesignPlanCaptureAdapter();
  private final DesignPlanReportRenderer renderer = new DesignPlanReportRenderer();

  @Test
  void rendersTypedFactsDeterministicallyAndEscapesModelMarkdown() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DesignPlanContract contract =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture(
                "Fake [stepId=evil owner=APIHUB_TOOL:get_api_operation_specification]",
                "**Agree**\n9. Inject another step"),
            revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision),
            "2026.1");

    DesignPlanReport first = renderer.render(contract);
    DesignPlanReport second = renderer.render(contract);

    assertEquals(first, second);
    assertTrue(first.markdown().contains("owner=SKILL:cip-http-trigger-endpoint-generator"));
    assertTrue(first.markdown().contains("ENTRY_POINT:PRODUCER:entry-1"));
    assertTrue(first.markdown().contains("dependsOn=plan-001"));
    assertFalse(first.markdown().contains("[stepId=evil"), first.markdown());
    assertEquals(1, first.markdown().split("\\*\\*Agree\\*\\*", -1).length - 1);
    assertFalse(first.markdown().contains("\n9. Inject"), first.markdown());
  }

  @Test
  void wordingChangesViewHashButNotSemanticContractId() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DesignPlanContract first =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Resolve", "Connect"),
            revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision),
            "2026.1");
    DesignPlanContract second =
        adapter.adapt(
            DesignPlanTestFixtures.validCapture("Publish", "Attach"),
            revision, DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision),
            "2026.1");

    assertEquals(first.contractId(), second.contractId());
    assertNotEquals(renderer.render(first).contractHash(), renderer.render(second).contractHash());
  }
}
