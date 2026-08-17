package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.A2aSdkBootProducers;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;

/**
 * Quarkus Arc smoke test for the CREATE product-pipeline CDI graph that failed bean discovery.
 */
@QuarkusTest
class ProductPipelineBeanWiringIT {

  @Inject CreateRunSelectionService selectionService;
  @Inject ProductPipelineChatAdapter chatAdapter;
  @Inject CreateProductPipelineCoordinator coordinator;
  @Inject CreateChainApplicationFacade facade;
  @Inject CreateChainOrchestrator createChainOrchestrator;
  @Inject ArtifactBlobStore artifactBlobStore;
  @Inject A2aSdkBootProducers a2aProducers;
  @Inject ScenarioRouter scenarioRouter;
  @Inject CompilerPlanningRunner compilerPlanningRunner;
  @Inject CompilerPlanningSpine compilerPlanningSpine;
  @Inject CompilerDerivedPlanningRunner compilerDerivedPlanningRunner;
  @Inject CompilerDerivedPlanningSpine compilerDerivedPlanningSpine;

  @Test
  void productCreateBeansAreResolvable() {
    assertNotNull(selectionService);
    assertNotNull(chatAdapter);
    assertNotNull(scenarioRouter);
    assertNotNull(compilerDerivedPlanningRunner);
    assertNotNull(compilerDerivedPlanningSpine);
    assertInstanceOf(
        ProvidedIdsFlowOrchestrator.class,
        ClientProxy.unwrap(createChainOrchestrator),
        "CDI must always provide the Flow-backed create-chain orchestrator");
  }

  @Test
  void compilerPlanningSpineIsWired() throws Exception {
    assertNotNull(compilerPlanningRunner, "CompilerPlanningRunner must inject");
    assertNotNull(compilerPlanningSpine, "CompilerPlanningSpine must inject");
    assertInstanceOf(
        CompilerDerivedPlanningSpine.class,
        ClientProxy.unwrap(compilerPlanningSpine),
        "CDI spine must be CompilerDerivedPlanningSpine (not an empty stub)");

    // ApplicationScoped injections are client proxies; inspect the contextual instance.
    CompilerPlanningRunner runner = ClientProxy.unwrap(compilerPlanningRunner);
    CompilerPlanningSpine spine = ClientProxy.unwrap(compilerPlanningSpine);

    Field asyncSpine = CompilerPlanningRunner.class.getDeclaredField("asyncSpine");
    asyncSpine.setAccessible(true);
    Field syncSpineExecutor = CompilerPlanningRunner.class.getDeclaredField("syncSpineExecutor");
    syncSpineExecutor.setAccessible(true);

    assertSame(
        spine,
        ClientProxy.unwrap(asyncSpine.get(runner)),
        "Runner must hold the injected CompilerPlanningSpine");
    assertNull(
        syncSpineExecutor.get(runner),
        "CDI path must not use the test-only syncSpineExecutor (null → empty spine → COMPILER_MISSING)");
  }

  @Test
  void browserAndA2aShareTheS3BackedFacade() throws Exception {
    assertNotNull(coordinator);
    assertNotNull(facade);
    CreateProductPipelineCoordinator unwrappedCoordinator = ClientProxy.unwrap(coordinator);
    CreateChainApplicationFacade unwrappedFacade = ClientProxy.unwrap(facade);
    Field facadeField = CreateProductPipelineCoordinator.class.getDeclaredField("facade");
    facadeField.setAccessible(true);
    assertSame(
        unwrappedFacade,
        ClientProxy.unwrap(facadeField.get(unwrappedCoordinator)),
        "browser coordinator must use the same CreateChainApplicationFacade bean as A2A");

    Field a2aFacade = A2aSdkBootProducers.class.getDeclaredField("facade");
    a2aFacade.setAccessible(true);
    assertSame(
        unwrappedFacade,
        ClientProxy.unwrap(a2aFacade.get(ClientProxy.unwrap(a2aProducers))),
        "A2A producers must use the same CreateChainApplicationFacade bean as the browser");

    Object blobs = ClientProxy.unwrap(artifactBlobStore);
    assertEquals(
        "org.qubership.integration.platform.ai.compiler.artifact.S3ArtifactBlobStore",
        blobs.getClass().getName(),
        "production facade evidence must persist through the S3 artifact blob store");
  }
}
