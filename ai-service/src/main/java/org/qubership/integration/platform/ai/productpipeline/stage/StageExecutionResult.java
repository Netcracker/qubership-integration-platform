package org.qubership.integration.platform.ai.productpipeline.stage;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;

/** Observable outcome of one stage-module invocation: a typed decision plus live signals. */
public record StageExecutionResult(StageDecision decision, List<PipelineSignal> signals) {

  public StageExecutionResult {
    Objects.requireNonNull(decision, "decision");
    signals = signals == null ? List.of() : List.copyOf(signals);
  }
}
