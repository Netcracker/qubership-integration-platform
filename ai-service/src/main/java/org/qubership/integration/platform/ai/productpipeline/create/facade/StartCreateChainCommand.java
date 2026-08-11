package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Objects;

/** Starts a create-chain execution. {@code taskId} is the pipeline conversation ID. */
public record StartCreateChainCommand(String taskId, String requirementText, String commandId) {

  public StartCreateChainCommand(String taskId, String requirementText) {
    this(taskId, requirementText, null);
  }

  public StartCreateChainCommand {
    Objects.requireNonNull(taskId, "taskId");
    if (taskId.isBlank()) {
      throw new IllegalArgumentException("taskId is required");
    }
    requirementText = requirementText == null ? "" : requirementText;
  }
}
