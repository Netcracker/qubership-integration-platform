package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-attempt cache of live catalog element descriptors.
 *
 * <p>Create one instance for each materialization attempt. The first {@link #require(String)} for a
 * type fetches the live catalog; later calls in the same instance reuse that descriptor. A new
 * instance fetches again. Load failures are not cached, so a retry still fails closed.
 */
public final class CatalogElementDescriptorCache {

  private final CatalogElementDescriptorLoader loader;
  private final Map<String, CatalogElementDescriptor> byType = new HashMap<>();

  public CatalogElementDescriptorCache(CatalogElementDescriptorLoader loader) {
    this.loader = Objects.requireNonNull(loader, "loader");
  }

  /**
   * Returns the descriptor for {@code type}, loading it once per cache instance.
   *
   * @throws CatalogElementDescriptorException if the type is unknown or the catalog cannot be read
   */
  public CatalogElementDescriptor require(String type) {
    CatalogElementDescriptor cached = byType.get(type);
    if (cached != null) {
      return cached;
    }
    CatalogElementDescriptor loaded = loader.load(type);
    byType.put(type, loaded);
    return loaded;
  }
}
