package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/**
 * Catalog identity recorded by Java. The model cannot supply this record. Protocol, method, and
 * path come from the resolved operation.
 */
public record ResolvedWorkBinding(
    String catalogId,
    String version,
    String operationId,
    String protocol,
    String method,
    String path,
    List<String> contractReferences,
    List<String> exposedPorts) {

  public ResolvedWorkBinding {
    protocol = protocol == null ? "" : protocol;
    method = method == null ? "" : method;
    path = path == null ? "" : path;
    contractReferences = Lists.copy(contractReferences);
    exposedPorts = Lists.copy(exposedPorts);
  }

  public ResolvedWorkBinding(
      String catalogId,
      String version,
      String operationId,
      List<String> contractReferences,
      List<String> exposedPorts) {
    this(catalogId, version, operationId, "", "", "", contractReferences, exposedPorts);
  }
}
