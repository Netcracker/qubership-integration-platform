package org.qubership.integration.platform.ai.integration.catalog.cache;

import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

/** Caller-scoped cache of catalog operation schemas, read through the CDI proxy. */
@ApplicationScoped
public class CatalogOperationSchemasReadCache {

  public static final String CACHE_NAME = "catalog-operation-schema";

  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogOperationSchemasReadCache(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  @CacheResult(cacheName = CACHE_NAME, keyGenerator = UserScopedCacheKeyGenerator.class)
  public CatalogRestClient.OperationSchemaMapsDto getOperationSchemas(String operationId) {
    return catalogRestClient.getOperationSchemas(operationId, "full");
  }

  @CacheInvalidate(cacheName = CACHE_NAME, keyGenerator = UserScopedCacheKeyGenerator.class)
  public void invalidate(String operationId) {}
}
