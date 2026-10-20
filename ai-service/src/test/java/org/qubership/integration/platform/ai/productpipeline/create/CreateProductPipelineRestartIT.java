package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class CreateProductPipelineRestartIT {

  private CreateProductPipelineCoordinatorTest.FixtureHelper helper;

  @BeforeEach
  void setUp() throws Exception {
    helper = CreateProductPipelineCoordinatorTest.FixtureHelper.create();
  }

  @Test
  void durableRunSurvivesCoordinatorReconstruction() {
    CreateProductPipelineCoordinator coordinator = helper.coordinator();
    org.qubership.integration.platform.ai.chat.model.ChatRequest request =
        new org.qubership.integration.platform.ai.chat.model.ChatRequest();
    request.setResolvedEffectiveUserText("create fortune API");
    coordinator.handle(request, "conv-restart-it").collect().asList().await().indefinitely();
    assertEquals(
        RunStatus.WAITING_FOR_APPROVAL,
        coordinator.loadRun("conv-restart-it").orElseThrow().run().status());

    coordinator.approveCurrent("conv-restart-it").collect().asList().await().indefinitely();

    CreateProductPipelineCoordinator restarted = helper.restartCoordinator();
    ProductPipelineRunDocument resumed = restarted.loadRun("conv-restart-it").orElseThrow();
    assertTrue(resumed.transitions().size() >= 2);
    assertEquals("conv-restart-it-create-chain-1", resumed.run().runId());
    assertNotNull(
        helper
            .selectionService()
            .existing("conv-restart-it")
            .orElseThrow()
            .runManifest()
            .compilerRunPin());
  }
}
