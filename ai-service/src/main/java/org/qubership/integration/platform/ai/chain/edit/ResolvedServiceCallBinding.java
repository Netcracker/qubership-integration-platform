package org.qubership.integration.platform.ai.chain.edit;

import java.util.Objects;

/**
 * One catalog operation, complete enough for the service-call generator to configure an element
 * from it.
 *
 * <p>The catalog refuses a service-call whose operation id, method and path describe different
 * operations, so these fields travel together as one value rather than as prose in a requirement
 * brief. Everything here is read from the catalog; nothing is inferred by a model.
 */
public record ResolvedServiceCallBinding(
    String targetNodeId,
    String systemType,
    String systemId,
    String specificationGroupId,
    String specificationId,
    String operationId,
    String protocolType,
    String method,
    String path,
    String displayName,
    Source source,
    String release,
    String evidenceRef) {

  public enum Source {
    EXISTING_CATALOG,
    APIHUB_IMPORT
  }

  public ResolvedServiceCallBinding {
    targetNodeId = requireText(targetNodeId, "targetNodeId");
    systemType = requireText(systemType, "systemType");
    systemId = requireText(systemId, "systemId");
    specificationGroupId = requireText(specificationGroupId, "specificationGroupId");
    specificationId = requireText(specificationId, "specificationId");
    operationId = requireText(operationId, "operationId");
    protocolType = requireText(protocolType, "protocolType");
    method = requireText(method, "method");
    path = requireText(path, "path");
    displayName = displayName == null ? "" : displayName.trim();
    source = Objects.requireNonNull(source, "source");
    release = release == null ? "" : release.trim();
    evidenceRef = evidenceRef == null ? "" : evidenceRef.trim();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
