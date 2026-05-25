package org.qubership.integration.platform.ai.integration.catalog.util;

import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

import java.util.Optional;

/** Reads ids from catalog {@link CatalogRestClient.ChainDiffDto} responses. */
public final class CatalogChainDiffs {

  public static final String UNKNOWN_ID = "unknown";

  private CatalogChainDiffs() {}

  public static Optional<String> firstCreatedElementId(CatalogRestClient.ChainDiffDto diff) {
    if (diff == null || diff.createdElements() == null || diff.createdElements().isEmpty()) {
      return Optional.empty();
    }
    String id = diff.createdElements().get(0).id();
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(id);
  }

  public static String firstCreatedElementIdOrUnknown(CatalogRestClient.ChainDiffDto diff) {
    return firstCreatedElementId(diff).orElse(UNKNOWN_ID);
  }

  public static String firstUpdatedElementIdOrUnknown(CatalogRestClient.ChainDiffDto diff) {
    if (diff != null && diff.updatedElements() != null && !diff.updatedElements().isEmpty()) {
      String id = diff.updatedElements().get(0).id();
      if (id != null && !id.isBlank()) {
        return id;
      }
    }
    return UNKNOWN_ID;
  }

  public static String firstCreatedDependencyIdOrUnknown(CatalogRestClient.ChainDiffDto diff) {
    if (diff != null
        && diff.createdDependencies() != null
        && !diff.createdDependencies().isEmpty()) {
      String id = diff.createdDependencies().get(0).id();
      if (id != null && !id.isBlank()) {
        return id;
      }
    }
    return UNKNOWN_ID;
  }
}
