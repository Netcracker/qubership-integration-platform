package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.Collection;

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

  /** Rejects a step, port, or retained id that this task does not own. */
  public void requireKnown(Collection<String> stepIds, Collection<String> ports, Collection<String> retainedIds) {
    if (!stepId.isBlank() && (stepIds == null || !stepIds.contains(stepId))) {
      throw new IllegalArgumentException(
          "Step " + stepId + " is outside this task. Name a step this task owns.");
    }
    if (!port.isBlank() && (ports == null || !ports.contains(port))) {
      throw new IllegalArgumentException(
          "Port " + port + " is outside this task. Name a port this task owns.");
    }
    if (!retainedValueId.isBlank() && (retainedIds == null || !retainedIds.contains(retainedValueId))) {
      throw new IllegalArgumentException(
          "Retained id " + retainedValueId + " is outside this task. Name a retained value this task owns.");
    }
  }
}
