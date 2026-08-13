package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;

class ProvidedIdsFlowTest {

  @Test
  void buildsTasksFromTheProfileThroughDesignPlanning() {
    ProductPipelineProfile profile = createChainV2();
    ProvidedIdsFlow flow = new ProvidedIdsFlow(profile, mock(ProvidedIdsFlowTasks.class));

    List<String> taskNames =
        flow.descriptor().getDo().stream().map(item -> item.getName()).toList();

    assertEquals(
        List.of(
            "ids-entry",
            "requirement-discovery",
            "import-stage",
            "requirement-analysis",
            "design-input",
            "design-planning"),
        taskNames);
    assertTrue(flow.ownsStage("design-planning"));
    assertFalse(flow.ownsStage("design-execution"));
  }

  @Test
  void stopsFlowTasksAfterIdsEntrySelectsTheStandardRoute() {
    ProductPipelineRuntime runtime = mock(ProductPipelineRuntime.class);
    when(runtime.executeStage("run-1", "ids-entry"))
        .thenReturn(Multi.createFrom().item(new PipelineSignal.Message("entry")));
    when(runtime.isProvidedDesignRoute("run-1")).thenReturn(false);
    ProvidedIdsFlowTasks tasks = new ProvidedIdsFlowTasks(runtime);
    ProvidedIdsFlow.RunInput input = tasks.begin("run-1");

    tasks.execute(input, "ids-entry").join();
    tasks.execute(input, "requirement-discovery").join();
    ProvidedIdsFlowTasks.Result result = tasks.finish(input);

    assertEquals(List.of(new PipelineSignal.Message("entry")), result.signals());
    assertTrue(result.standardRoute());
    verify(runtime).executeStage("run-1", "ids-entry");
    verify(runtime).isProvidedDesignRoute("run-1");
    verifyNoMoreInteractions(runtime);
  }

  @Test
  void keepsExecutingProfileTasksForTheProvidedRoute() {
    ProductPipelineRuntime runtime = mock(ProductPipelineRuntime.class);
    when(runtime.executeStage("run-1", "ids-entry"))
        .thenReturn(Multi.createFrom().item(new PipelineSignal.Message("entry")));
    when(runtime.executeStage("run-1", "requirement-discovery"))
        .thenReturn(Multi.createFrom().empty());
    when(runtime.isProvidedDesignRoute("run-1")).thenReturn(true);
    ProvidedIdsFlowTasks tasks = new ProvidedIdsFlowTasks(runtime);
    ProvidedIdsFlow.RunInput input = tasks.begin("run-1");

    tasks.execute(input, "ids-entry").join();
    tasks.execute(input, "requirement-discovery").join();
    ProvidedIdsFlowTasks.Result result = tasks.finish(input);

    assertFalse(result.standardRoute());
    verify(runtime).executeStage("run-1", "requirement-discovery");
  }

  private static ProductPipelineProfile createChainV2() {
    try (InputStream input =
        ProvidedIdsFlowTest.class.getResourceAsStream(
            "/product-pipelines/profiles/create-chain-v2.yaml")) {
      if (input == null) {
        throw new IllegalStateException("create-chain-v2 profile is missing");
      }
      return ProductPipelineProfileParser.parse(input);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
