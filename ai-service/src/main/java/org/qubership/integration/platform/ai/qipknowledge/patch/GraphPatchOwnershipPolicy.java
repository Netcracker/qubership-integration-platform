package org.qubership.integration.platform.ai.qipknowledge.patch;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable ownership boundary for graph patch operations. */
public record GraphPatchOwnershipPolicy(
    boolean mayAddNodes,
    boolean mayAddEdges,
    Set<String> nodeTypes,
    Set<String> chainFields,
    Map<String, Set<String>> properties) {

  public GraphPatchOwnershipPolicy {
    nodeTypes = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
    chainFields = chainFields == null ? Set.of() : Set.copyOf(chainFields);
    properties = normalizeProperties(properties);
  }

  public static GraphPatchOwnershipPolicy denyAll() {
    return new GraphPatchOwnershipPolicy(false, false, Set.of(), Set.of(), Map.of());
  }

  private static Map<String, Set<String>> normalizeProperties(Map<String, Set<String>> properties) {
    if (properties == null || properties.isEmpty()) {
      return Map.of();
    }
    Map<String, Set<String>> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : properties.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      Set<String> value = entry.getValue();
      if (value == null || value.isEmpty()) {
        normalized.put(key, Set.of());
        continue;
      }
      Set<String> normalizedValues = new LinkedHashSet<>();
      for (String property : value) {
        if (property == null || property.isBlank()) {
          continue;
        }
        normalizedValues.add(property);
      }
      normalized.put(key, Set.copyOf(normalizedValues));
    }
    return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
  }
}
