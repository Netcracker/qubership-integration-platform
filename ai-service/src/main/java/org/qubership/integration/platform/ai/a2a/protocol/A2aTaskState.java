package org.qubership.integration.platform.ai.a2a.protocol;

import org.a2aproject.sdk.spec.TaskState;

/**
 * Application-facing A2A task states used by protocol helpers.
 */
public enum A2aTaskState {
  SUBMITTED(TaskState.TASK_STATE_SUBMITTED),
  WORKING(TaskState.TASK_STATE_WORKING),
  INPUT_REQUIRED(TaskState.TASK_STATE_INPUT_REQUIRED),
  COMPLETED(TaskState.TASK_STATE_COMPLETED),
  FAILED(TaskState.TASK_STATE_FAILED);

  private final TaskState sdkState;

  A2aTaskState(TaskState sdkState) {
    this.sdkState = sdkState;
  }

  public TaskState toSdk() {
    return sdkState;
  }
}
