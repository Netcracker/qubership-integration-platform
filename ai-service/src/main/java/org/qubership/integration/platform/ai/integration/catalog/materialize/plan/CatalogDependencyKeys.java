package org.qubership.integration.platform.ai.integration.catalog.materialize.plan;

import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Normalizes catalog dependency endpoints into comparable edge keys (catalog element ids). */
public final class CatalogDependencyKeys {

  private CatalogDependencyKeys() {}

  public static String edgeKey(String from, String to) {
    return from + "|" + to;
  }

  /**
   * Builds a set of edge keys {@code from|to} for non-null dependency rows with both endpoints
   * present.
   */
  public static Set<String> edgeKeysFromDependencies(List<CatalogDependencyDto> deps) {
    Set<String> keys = new HashSet<>();
    if (deps == null) {
      return keys;
    }
    for (CatalogDependencyDto d : deps) {
      if (d == null) {
        continue;
      }
      String f = CatalogStrings.blankToNull(d.from);
      String t = CatalogStrings.blankToNull(d.to);
      if (f != null && t != null) {
        keys.add(edgeKey(f, t));
      }
    }
    return keys;
  }
}
