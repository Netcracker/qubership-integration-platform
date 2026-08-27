package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;

/** Continues a create-chain execution with clarification or free-form input. */
public record ContinueCreateChainCommand(
    String taskId, String clarificationText, String commandId, InputOrigin origin) {

  public ContinueCreateChainCommand(String taskId, String clarificationText) {
    this(taskId, clarificationText, null, InputOrigin.ABSENT);
  }

  public ContinueCreateChainCommand(String taskId, String clarificationText, String commandId) {
    this(taskId, clarificationText, commandId, InputOrigin.ABSENT);
  }

  public ContinueCreateChainCommand {
    Objects.requireNonNull(taskId, "taskId");
    if (taskId.isBlank()) {
      throw new IllegalArgumentException("taskId is required");
    }
    clarificationText = clarificationText == null ? "" : clarificationText;
    origin = InputOrigin.of(origin);
  }
}
