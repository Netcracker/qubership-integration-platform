package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkus.arc.ClientProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;

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
  @Inject ProductPipelineRunSupport runSupport;
  @Inject ObjectMapper objectMapper;
  @Inject SchemaResourceLoader schemaResourceLoader;
  @Inject SchemaRefResolver schemaRefResolver;
  @Inject DeterministicElementSchemaService schemaService;

  @Test
  void recoveryUsesApplicationSchemaBeans() throws Exception {
    Object executor = runSupport.stageExecutor();
    for (var dependency : java.util.Map.of(
        "objectMapper", objectMapper,
        "schemaResourceLoader", schemaResourceLoader,
        "schemaRefResolver", schemaRefResolver,
        "schemaService", schemaService).entrySet()) {
      Field field = ProductPipelineStageExecutor.class.getDeclaredField(dependency.getKey());
      field.setAccessible(true);
      assertSame(ClientProxy.unwrap(dependency.getValue()), ClientProxy.unwrap(field.get(executor)),
          "Recovery must use the application bean for " + dependency.getKey());
    }
  }

  @Test
  void productCreateBeansAreResolvable() {
    assertNotNull(selectionService);
    assertNotNull(chatAdapter);
    assertNotNull(scenarioRouter);
    assertInstanceOf(
        ProvidedIdsFlowOrchestrator.class,
        ClientProxy.unwrap(createChainOrchestrator),
        "CDI must always provide the Flow-backed create-chain orchestrator");
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
