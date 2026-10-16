package org.qubership.integration.platform.ai.integration.catalog.util;

import java.util.LinkedHashMap;
import java.util.Map;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

/** Preserves catalog defaults on patch and identifies the primary element in a create response. */
public final class CatalogElementWrites {

  private CatalogElementWrites() {}

  public static Map<String, Object> mergeWithCurrentElement(
      CatalogElementResponseDto current, Map<String, Object> patch) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (current != null && current.name != null && !current.name.isBlank()) {
      merged.put("name", current.name);
    }
    if (current != null && current.description != null) {
      merged.put("description", current.description);
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    if (current != null && current.properties != null) {
      properties.putAll(current.properties);
    }
    Object patchProperties = patch.get("properties");
    if (patchProperties instanceof Map<?, ?> patchMap) {
      for (Map.Entry<?, ?> entry : patchMap.entrySet()) {
        if (entry.getKey() != null) {
          properties.put(String.valueOf(entry.getKey()), entry.getValue());
        }
      }
    }
    for (Map.Entry<String, Object> entry : patch.entrySet()) {
      if (!"properties".equals(entry.getKey())) {
        merged.put(entry.getKey(), entry.getValue());
      }
    }
    merged.put("properties", properties);
    return merged;
  }

  public static String extractCreatedElementId(
      CatalogRestClient.ChainDiffDto diff, String expectedType) {
    if (diff == null || diff.createdElements() == null || diff.createdElements().isEmpty()) {
      throw new IllegalStateException("createElement did not return created elements");
    }
    String want = expectedType != null ? expectedType.trim() : "";
    for (CatalogRestClient.ElementSummaryDto element : diff.createdElements()) {
      if (element == null) {
        continue;
      }
      String type = element.type() != null ? element.type().trim() : "";
      if (want.equals(type)) {
        String elementId = element.id();
        if (elementId == null || elementId.isBlank()) {
          throw new IllegalStateException("createElement returned empty element id");
        }
        return elementId;
      }
    }
    throw new IllegalStateException("createElement response has no element of type " + expectedType);
  }
}
