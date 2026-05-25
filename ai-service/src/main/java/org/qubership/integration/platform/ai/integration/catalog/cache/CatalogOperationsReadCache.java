package org.qubership.integration.platform.ai.integration.catalog.cache;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-conversation cache for paginated catalog operation list HTTP reads (by specification
 * modelId).
 */
@ApplicationScoped
public class CatalogOperationsReadCache {

  static final int PAGE_SIZE = 500;

  static final int MAX_OPERATIONS_TOTAL = 5000;

  public static final String CACHE_NAME = "catalog-operations-by-model";

  private static final Logger LOG = Logger.getLogger(CatalogOperationsReadCache.class);

  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogOperationsReadCache(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  @CacheResult(cacheName = CACHE_NAME)
  public List<CatalogRestClient.OperationDto> loadByModelId(String modelId) {
    return loadByModelIdInternal(modelId);
  }

  /** Bypasses Quarkus cache for binding refresh when a cached empty list may be stale. */
  public List<CatalogRestClient.OperationDto> loadByModelIdUncached(String modelId) {
    return loadByModelIdInternal(modelId);
  }

  private List<CatalogRestClient.OperationDto> loadByModelIdInternal(String modelId) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (mid == null) {
      return List.of();
    }
    List<CatalogRestClient.OperationDto> all = new ArrayList<>();
    int offset = 0;
    boolean hasMore = true;
    while (all.size() < MAX_OPERATIONS_TOTAL && hasMore) {
      List<CatalogRestClient.OperationDto> page =
          catalogRestClient.getOperations(mid, offset, PAGE_SIZE, null);
      if (page != null && !page.isEmpty()) {
        all.addAll(page);
        hasMore = page.size() >= PAGE_SIZE;
        offset += PAGE_SIZE;
      } else {
        hasMore = false;
      }
    }
    if (all.size() >= MAX_OPERATIONS_TOTAL) {
      LOG.warnf(
          "Operations list for modelId=%s reached cap %d; remaining operations omitted",
          mid, MAX_OPERATIONS_TOTAL);
    }
    return List.copyOf(all);
  }
}
