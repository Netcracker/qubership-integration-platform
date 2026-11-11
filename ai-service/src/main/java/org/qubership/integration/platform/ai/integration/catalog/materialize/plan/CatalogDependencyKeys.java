package org.qubership.integration.platform.ai.integration.catalog.materialize.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

/** Normalizes catalog dependency endpoints into comparable edge keys (catalog element ids). */
public final class CatalogDependencyKeys {

  private CatalogDependencyKeys() {}

  public static String edgeKey(String from, String to) {
    return from + "|" + to;
  }

  public static Set<String> edgeKeysFromDependencies(List<CatalogDependencyDto> deps) {
    Set<String> keys = new HashSet<>();
    if (deps == null) {
      return keys;
    }
    for (CatalogDependencyDto dep : deps) {
      if (dep == null) {
        continue;
      }
      String from = CatalogStrings.blankToNull(dep.from);
      String to = CatalogStrings.blankToNull(dep.to);
      if (from != null && to != null) {
        keys.add(edgeKey(from, to));
      }
    }
    return keys;
  }
}
