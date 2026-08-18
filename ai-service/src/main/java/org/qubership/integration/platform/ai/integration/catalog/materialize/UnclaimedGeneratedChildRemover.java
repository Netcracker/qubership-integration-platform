package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptor;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;

/**
 * Deletes optional catalog-generated children that the desired graph did not claim.
 *
 * <p>Only descendants recorded from a container create in this attempt are considered. Preexisting
 * parents are never pruned.
 */
@ApplicationScoped
public class UnclaimedGeneratedChildRemover {

  private static final Logger LOG = Logger.getLogger(UnclaimedGeneratedChildRemover.class);

  private final CatalogRestClient catalogRestClient;

  @Inject
  public UnclaimedGeneratedChildRemover(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
  }

  void removeUnclaimed(
      String chainId,
      Map<String, String> nodeIdToElementId,
      MaterializationAttemptContext attempt,
      CatalogElementDescriptorCache cache) {
    Objects.requireNonNull(chainId, "chainId");
    Objects.requireNonNull(nodeIdToElementId, "nodeIdToElementId");
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(cache, "cache");
    if (attempt.isEmpty()) {
      return;
    }

    Collection<String> mappedElementIds = nodeIdToElementId.values();
    List<String> toDelete = new ArrayList<>();
    for (MaterializationAttemptContext.CreatedContainer container : attempt.createdContainers()) {
      CatalogElementDescriptor descriptor = cache.require(container.type());
      Map<String, CatalogChildQuantity> allowedChildren = descriptor.allowedChildren();
      if (allowedChildren.isEmpty()) {
        continue;
      }
      for (CatalogRestClient.ElementSummaryDto generated : container.generatedChildren()) {
        String childId = trim(generated.id());
        String childType = trim(generated.type());
        if (childId == null || childType == null || mappedElementIds.contains(childId)) {
          continue;
        }
        CatalogChildQuantity quantity = allowedChildren.get(childType);
        if (quantity != null && quantity.minimum() == 0) {
          toDelete.add(childId);
        }
      }
    }

    if (toDelete.isEmpty()) {
      return;
    }
    LOG.infof(
        "Removing %d unclaimed generated children from chain %s: %s",
        toDelete.size(), chainId, String.join(", ", toDelete));
    catalogRestClient.deleteElements(chainId, List.copyOf(toDelete));
  }

  private static String trim(String value) {
    return value != null ? value.trim() : null;
  }
}
