package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;

/**
 * Quarkus Arc smoke test for the CREATE product-pipeline CDI graph that failed bean discovery.
 */
@QuarkusTest
class ProductPipelineBeanWiringIT {

  @Inject CreateRunSelectionService selectionService;
  @Inject ProductPipelineChatAdapter chatAdapter;
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
}
