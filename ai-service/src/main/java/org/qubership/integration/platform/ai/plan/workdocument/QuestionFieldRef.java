package org.qubership.integration.platform.ai.plan.workdocument;

/** Validated step, port, and field a question subject points at. */
public record QuestionFieldRef(String stepId, String port, String fieldPath, String retainedValueId) {

  public QuestionFieldRef {
    stepId = stepId == null ? "" : stepId;
    port = port == null ? "" : port;
    fieldPath = fieldPath == null ? "" : fieldPath;
    retainedValueId = retainedValueId == null ? "" : retainedValueId;
  }

  public static QuestionFieldRef empty() {
    return new QuestionFieldRef("", "", "", "");
  }
}
