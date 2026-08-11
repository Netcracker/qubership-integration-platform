package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Objects;

/** Continues a create-chain execution with clarification or free-form input. */
public record ContinueCreateChainCommand(String taskId, String clarificationText, String commandId) {

  public ContinueCreateChainCommand(String taskId, String clarificationText) {
    this(taskId, clarificationText, null);
  }

  public ContinueCreateChainCommand {
    Objects.requireNonNull(taskId, "taskId");
    if (taskId.isBlank()) {
      throw new IllegalArgumentException("taskId is required");
    }
    clarificationText = clarificationText == null ? "" : clarificationText;
  }
}
