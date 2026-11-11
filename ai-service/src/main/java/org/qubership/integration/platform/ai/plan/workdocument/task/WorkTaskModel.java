package org.qubership.integration.platform.ai.plan.workdocument.task;

/** Provider boundary for one scoped task. Tests and the checkpoint adapter implement this. */
public interface WorkTaskModel {

  String complete(WorkTaskRequest request);
}
