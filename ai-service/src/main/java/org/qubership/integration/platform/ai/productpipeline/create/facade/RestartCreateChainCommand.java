package org.qubership.integration.platform.ai.productpipeline.create.facade;

import org.qubership.integration.platform.ai.productpipeline.runtime.RestartCheckpoint;

/** Requests a derived run from a checkpoint visible at one exact parent revision. */
public record RestartCreateChainCommand(
    String taskId, long expectedRunRevision, RestartCheckpoint checkpoint, String commandId) {

  public RestartCreateChainCommand {
    if (taskId == null || taskId.isBlank()) {
      throw new IllegalArgumentException("taskId is required");
    }
    if (expectedRunRevision < 1) {
      throw new IllegalArgumentException("expectedRunRevision must be >= 1");
    }
    if (checkpoint == null) {
      throw new IllegalArgumentException("checkpoint is required");
    }
    if (commandId == null || commandId.isBlank()) {
      throw new IllegalArgumentException("commandId is required");
    }
  }
}
