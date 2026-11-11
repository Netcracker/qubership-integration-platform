package org.qubership.integration.platform.ai.integration.catalog.lookup;

import java.util.List;

/** Outcome of one catalog operation lookup. A too-broad catalog is not a miss. */
public sealed interface CatalogLookupResult {

  record Exact(CatalogMatch match) implements CatalogLookupResult {}

  record Ambiguous(List<String> candidateIds, List<CatalogMatch> candidates) implements CatalogLookupResult {
    public Ambiguous(List<String> candidateIds) {
      this(candidateIds, List.of());
    }
  }

  record None() implements CatalogLookupResult {}

  record TooBroad(int candidateCount) implements CatalogLookupResult {}
}
