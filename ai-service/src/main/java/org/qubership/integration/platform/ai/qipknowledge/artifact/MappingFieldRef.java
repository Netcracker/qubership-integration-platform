package org.qubership.integration.platform.ai.qipknowledge.artifact;

/** A structured field or retained-value reference on a descriptive mapping rule. */
public record MappingFieldRef(String stepId, String port, String fieldPath, String retainedValueId) {

  public MappingFieldRef {
    stepId = stepId == null ? "" : stepId;
    port = port == null ? "" : port;
    fieldPath = fieldPath == null ? "" : fieldPath;
    retainedValueId = retainedValueId == null ? "" : retainedValueId;
  }
}
