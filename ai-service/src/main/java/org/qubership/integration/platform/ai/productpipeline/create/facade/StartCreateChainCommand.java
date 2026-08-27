package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;

/** Starts a create-chain execution. {@code taskId} is the pipeline conversation ID. */
public record StartCreateChainCommand(
    String taskId, String requirementText, String commandId, InputOrigin origin) {

  public StartCreateChainCommand(String taskId, String requirementText) {
    this(taskId, requirementText, null, InputOrigin.ABSENT);
  }

  public StartCreateChainCommand(String taskId, String requirementText, String commandId) {
    this(taskId, requirementText, commandId, InputOrigin.ABSENT);
  }

  public StartCreateChainCommand {
    Objects.requireNonNull(taskId, "taskId");
    if (taskId.isBlank()) {
      throw new IllegalArgumentException("taskId is required");
    }
    requirementText = requirementText == null ? "" : requirementText;
    origin = InputOrigin.of(origin);
  }
}
