package org.qubership.integration.platform.ai.qipknowledge.patch;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable ownership boundary for graph patch operations. */
public record GraphPatchOwnershipPolicy(
    boolean mayAddNodes,
    boolean mayAddEdges,
    boolean mayRemoveNodes,
    boolean mayRemoveEdges,
    Set<String> nodeTypes,
    Set<String> chainFields,
    Map<String, Set<String>> properties) {

  public GraphPatchOwnershipPolicy {
    nodeTypes = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
    chainFields = chainFields == null ? Set.of() : Set.copyOf(chainFields);
    properties = normalizeProperties(properties);
  }

  /**
   * Additive-only policy: removal is off.
   *
   * <p>Kept so the callers that predate removal read as what they are -- policies that never
   * intended to delete anything -- rather than each having to spell out two false flags.
   */
  public GraphPatchOwnershipPolicy(
      boolean mayAddNodes,
      boolean mayAddEdges,
      Set<String> nodeTypes,
      Set<String> chainFields,
      Map<String, Set<String>> properties) {
    this(mayAddNodes, mayAddEdges, false, false, nodeTypes, chainFields, properties);
  }

  public static GraphPatchOwnershipPolicy denyAll() {
    return new GraphPatchOwnershipPolicy(false, false, false, false, Set.of(), Set.of(), Map.of());
  }

  /** Returns this policy plus {@code extra} property keys. Same instance when nothing is added. */
  public GraphPatchOwnershipPolicy withAdditionalProperties(Map<String, Set<String>> extra) {
    if (extra == null || extra.isEmpty()) {
      return this;
    }
    Map<String, Set<String>> merged = new LinkedHashMap<>(properties);
    boolean changed = false;
    for (Map.Entry<String, Set<String>> entry : extra.entrySet()) {
      String type = entry.getKey();
      if (type == null || type.isBlank() || entry.getValue() == null || entry.getValue().isEmpty()) {
        continue;
      }
      Set<String> keys = new LinkedHashSet<>(merged.getOrDefault(type, Set.of()));
      int before = keys.size();
      boolean hadType = merged.containsKey(type);
      keys.addAll(entry.getValue());
      if (hadType && keys.size() == before) {
        continue;
      }
      merged.put(type, Set.copyOf(keys));
      changed = true;
    }
    if (!changed) {
      return this;
    }
    return new GraphPatchOwnershipPolicy(
        mayAddNodes,
        mayAddEdges,
        mayRemoveNodes,
        mayRemoveEdges,
        nodeTypes,
        chainFields,
        merged);
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
