package org.qubership.integration.platform.ai.productpipeline.create.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinator;
import org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;

class CreateChainOrchestrationBoundaryTest {

  @Test
  void applicationFacadesDependOnTheFlowFreeOrchestrationPort() throws Exception {
    assertTrue(CreateChainOrchestrator.class.isAssignableFrom(ProvidedIdsFlowOrchestrator.class));
    assertEquals(
        CreateChainOrchestrator.class,
        CreateChainApplicationFacade.class.getDeclaredField("runtime").getType());
    assertEquals(
        CreateChainApplicationFacade.class,
        CreateProductPipelineCoordinator.class.getDeclaredField("facade").getType());
    assertTrue(
        Arrays.stream(CreateProductPipelineCoordinator.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("runtime")),
        "browser coordinator must not hold a CreateChainOrchestrator runtime field");

    Stream<Class<?>> exposedTypes =
        Arrays.stream(CreateChainOrchestrator.class.getMethods())
            .flatMap(
                method ->
                    Stream.concat(
                        Stream.of(method.getReturnType()), Arrays.stream(method.getParameterTypes())));
    assertTrue(exposedTypes.noneMatch(type -> type.getName().startsWith("io.quarkiverse.flow")));
  }
}
