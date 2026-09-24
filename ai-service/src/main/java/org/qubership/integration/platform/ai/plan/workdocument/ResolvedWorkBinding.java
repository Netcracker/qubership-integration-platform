package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/** Catalog identity recorded by Java. The model cannot supply this record. */
public record ResolvedWorkBinding(
    String catalogId,
    String version,
    String operationId,
    List<String> contractReferences,
    List<String> exposedPorts) {

  public ResolvedWorkBinding {
    contractReferences = Lists.copy(contractReferences);
    exposedPorts = Lists.copy(exposedPorts);
  }
}
