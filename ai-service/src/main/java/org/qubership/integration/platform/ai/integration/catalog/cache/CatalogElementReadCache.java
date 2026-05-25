package org.qubership.integration.platform.ai.integration.catalog.cache;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

/** Short-TTL cache for catalog {@code GET element} reads during patch preparation bursts. */
@ApplicationScoped
public class CatalogElementReadCache {

  public static final String CACHE_NAME = "catalog-element";

  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogElementReadCache(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  @CacheResult(cacheName = CACHE_NAME)
  public CatalogElementResponseDto getElement(String chainId, String elementId) {
    return catalogRestClient.getElement(chainId, elementId);
  }
}
