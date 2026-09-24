package org.qubership.integration.platform.ai.plan.workdocument.task;

/** Provider boundary for one task prompt. Tests supply a fake. */
public interface WorkTaskModel {

  String complete(String prompt);
}
