package org.qubership.integration.platform.ai.productpipeline.create.flow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;

/** Executes one existing capability stage for each task in the create-chain Flow. */
@ApplicationScoped
public class ProvidedIdsFlowTasks {

  private final ProductPipelineRuntime runtime;
  private final Map<String, Invocation> invocations = new ConcurrentHashMap<>();

  @Inject
  public ProvidedIdsFlowTasks(ProductPipelineRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  ProvidedIdsFlow.RunInput begin(String runId) {
    String invocationId = UUID.randomUUID().toString();
    invocations.put(invocationId, new Invocation(runId));
    return new ProvidedIdsFlow.RunInput(runId, invocationId);
  }

  CompletableFuture<ProvidedIdsFlow.RunInput> execute(
      ProvidedIdsFlow.RunInput input, String stageId) {
    Invocation invocation = requireInvocation(input);
    return runtime
        .executeStage(input.runId(), stageId)
        .collect()
        .asList()
        .subscribeAsCompletionStage()
        .toCompletableFuture()
        .thenApply(
            signals -> {
              invocation.signals.addAll(signals);
              return input;
            });
  }

  Result finish(ProvidedIdsFlow.RunInput input) {
    Invocation invocation = invocations.remove(input.invocationId());
    if (invocation == null) {
      return new Result(List.of());
    }
    return new Result(List.copyOf(invocation.signals));
  }

  void discard(ProvidedIdsFlow.RunInput input) {
    invocations.remove(input.invocationId());
  }

  private Invocation requireInvocation(ProvidedIdsFlow.RunInput input) {
    Objects.requireNonNull(input, "input");
    Invocation invocation = invocations.get(input.invocationId());
    if (invocation == null || !invocation.runId.equals(input.runId())) {
      throw new IllegalStateException("unknown Flow invocation " + input.invocationId());
    }
    return invocation;
  }

  record Result(List<PipelineSignal> signals) {}

  private static final class Invocation {
    private final String runId;
    private final List<PipelineSignal> signals = new ArrayList<>();

    private Invocation(String runId) {
      this.runId = runId;
    }
  }
}
