package org.qubership.integration.platform.ai.chain.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Flat catalog element for chain presentation and reconcile. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainCatalogElement(
    String elementId,
    String type,
    String name,
    String parentElementId,
    Map<String, Object> properties) {

  public ChainCatalogElement {
    properties = properties == null ? Map.of() : Map.copyOf(properties);
  }

  public ChainCatalogElement(
      String elementId,
      String type,
      String name,
      String parentElementId,
      String serviceId,
      String operationId,
      String protocol,
      Map<String, String> scriptProperties) {
    this(
        elementId,
        type,
        name,
        parentElementId,
        mergeLegacyProperties(serviceId, operationId, protocol, scriptProperties));
  }

  public Map<String, String> scriptProperties() {
    if (!"script".equals(type)) {
      return Map.of();
    }
    return properties.entrySet().stream()
        .filter(entry -> entry.getValue() instanceof String)
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> (String) entry.getValue()));
  }

  static Map<String, Object> mergeLegacyProperties(
      String serviceId,
      String operationId,
      String protocol,
      Map<String, String> scriptProperties) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (serviceId != null && !serviceId.isBlank()) {
      merged.put("serviceId", serviceId);
    }
    if (operationId != null && !operationId.isBlank()) {
      merged.put("operationId", operationId);
    }
    if (protocol != null && !protocol.isBlank()) {
      merged.put("protocol", protocol);
    }
    if (scriptProperties != null) {
      for (Map.Entry<String, String> entry : scriptProperties.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          merged.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return Map.copyOf(merged);
  }
}
