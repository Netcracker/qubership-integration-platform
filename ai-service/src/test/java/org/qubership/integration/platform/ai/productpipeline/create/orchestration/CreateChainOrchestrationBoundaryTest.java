package org.qubership.integration.platform.ai.productpipeline.create.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinator;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;

class CreateChainOrchestrationBoundaryTest {

  @Test
  void applicationFacadesDependOnTheFlowFreeOrchestrationPort() throws Exception {
    assertTrue(CreateChainOrchestrator.class.isAssignableFrom(ProductPipelineRuntime.class));
    assertEquals(
        CreateChainOrchestrator.class,
        CreateChainApplicationFacade.class.getDeclaredField("runtime").getType());
    assertEquals(
        CreateChainOrchestrator.class,
        CreateProductPipelineCoordinator.class.getDeclaredField("runtime").getType());

    Stream<Class<?>> exposedTypes =
        Arrays.stream(CreateChainOrchestrator.class.getMethods())
            .flatMap(
                method ->
                    Stream.concat(
                        Stream.of(method.getReturnType()), Arrays.stream(method.getParameterTypes())));
    assertTrue(exposedTypes.noneMatch(type -> type.getName().startsWith("io.quarkiverse.flow")));
  }
}
