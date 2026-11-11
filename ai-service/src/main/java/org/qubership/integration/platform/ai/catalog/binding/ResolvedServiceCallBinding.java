package org.qubership.integration.platform.ai.catalog.binding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    String serviceCallId,
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
    String evidenceRef,
    String packageId,
    String maasClassifierName,
    String groupId) {

  public enum Source {
    EXISTING_CATALOG,
    APIHUB_IMPORT
  }

  public ResolvedServiceCallBinding {
    targetNodeId = requireText(targetNodeId, "targetNodeId");
    serviceCallId = requireText(serviceCallId, "serviceCallId");
    systemType = requireText(systemType, "systemType");
    systemId = requireText(systemId, "systemId");
    specificationGroupId = requireText(specificationGroupId, "specificationGroupId");
    specificationId = requireText(specificationId, "specificationId");
    operationId = requireText(operationId, "operationId");
    protocolType = requireText(protocolType, "protocolType");
    method = requireText(method, "method");
    path = path == null ? "" : path;
    displayName = displayName == null ? "" : displayName.trim();
    source = Objects.requireNonNull(source, "source");
    release = release == null ? "" : release.trim();
    evidenceRef = evidenceRef == null ? "" : evidenceRef.trim();
    packageId = packageId == null ? "" : packageId.trim();
    maasClassifierName = maasClassifierName == null ? "" : maasClassifierName.trim();
    groupId = groupId == null ? "" : groupId.trim();
  }

  public ResolvedServiceCallBinding(
      String targetNodeId,
      String serviceCallId,
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
      String evidenceRef,
      String packageId) {
    this(
        targetNodeId,
        serviceCallId,
        systemType,
        systemId,
        specificationGroupId,
        specificationId,
        operationId,
        protocolType,
        method,
        path,
        displayName,
        source,
        release,
        evidenceRef,
        packageId,
        "",
        "");
  }

  /**
   * Indexes bindings by {@code serviceCallId}. The same operation UUID on two occurrences stays two
   * keys. Missing, duplicate, or extra bindings fail fast.
   */
  public static Map<String, ResolvedServiceCallBinding> requireExactOwners(
      List<String> serviceCallIds, List<ResolvedServiceCallBinding> bindings) {
    Objects.requireNonNull(serviceCallIds, "serviceCallIds");
    Objects.requireNonNull(bindings, "bindings");
    Map<String, ResolvedServiceCallBinding> byId = new LinkedHashMap<>();
    for (ResolvedServiceCallBinding binding : bindings) {
      if (binding == null) {
        throw new IllegalArgumentException("catalog binding is required");
      }
      ResolvedServiceCallBinding previous = byId.putIfAbsent(binding.serviceCallId(), binding);
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate catalog binding for serviceCallId=" + binding.serviceCallId());
      }
    }
    Map<String, ResolvedServiceCallBinding> matched = new LinkedHashMap<>();
    for (String serviceCallId : serviceCallIds) {
      if (serviceCallId == null || serviceCallId.isBlank()) {
        throw new IllegalArgumentException("service call is required");
      }
      ResolvedServiceCallBinding binding = byId.remove(serviceCallId);
      if (binding == null) {
        throw new IllegalArgumentException(
            "missing catalog binding for serviceCallId=" + serviceCallId);
      }
      matched.put(serviceCallId, binding);
    }
    if (!byId.isEmpty()) {
      throw new IllegalArgumentException(
          "extra catalog binding for serviceCallId=" + byId.keySet().iterator().next());
    }
    return Map.copyOf(matched);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
