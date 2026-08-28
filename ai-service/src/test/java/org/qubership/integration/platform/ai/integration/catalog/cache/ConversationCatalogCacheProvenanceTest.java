package org.qubership.integration.platform.ai.integration.catalog.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

/**
 * Provenance of a specification id learned while listing operations.
 *
 * <p>Bindings are checked against ids the catalog actually returned in this conversation, which
 * stops invented UUIDs. Listing operations returns the specification id on every entry, so an id
 * learned that way is as genuine as one from the specification list — and refusing it blocks a
 * binding the caller discovered on a route the tools themselves offer.
 */
class ConversationCatalogCacheProvenanceTest {

  private static final String CONVERSATION = "conv-1";
  private static final String MODEL_ID = "petstore-swagger-1.0.7";

  @Test
  void specificationIdFromListedOperationsCountsAsObserved() {
    ConversationCatalogCache cache = cacheReturning(List.of(operation()));

    assertFalse(
        cache.isKnownSpecificationId(CONVERSATION, MODEL_ID),
        "nothing was listed yet, so the id has no provenance");

    cache.getOrLoadOperations(CONVERSATION, MODEL_ID, "petstore-system");

    assertTrue(
        cache.isKnownSpecificationId(CONVERSATION, MODEL_ID),
        "listing operations returns the specification id and must establish provenance");
    assertFalse(
        cache.isKnownSpecificationId(CONVERSATION, "made-up-specification"),
        "an id the catalog never returned stays unknown");
  }

  @Test
  void findOperationDecodesPercentEncodedIds() {
    ConversationCatalogCache cache = new ConversationCatalogCache(null);
    CatalogRestClient.OperationDto listed =
        new CatalogRestClient.OperationDto(
            "WFMS Create Work Order", "WFMS Create Work Order", "POST", "/workOrder", MODEL_ID);
    cache.rememberOperation(CONVERSATION, listed);

    assertTrue(cache.findOperation(CONVERSATION, "WFMS Create Work Order").isPresent());
    assertTrue(cache.findOperation(CONVERSATION, "WFMS%20Create%20Work%20Order").isPresent());
  }

  private static CatalogRestClient.OperationDto operation() {
    return new CatalogRestClient.OperationDto(
        MODEL_ID + "-getInventory", "getInventory", "GET", "/store/inventory", MODEL_ID);
  }

  private static ConversationCatalogCache cacheReturning(
      List<CatalogRestClient.OperationDto> operations) {
    return new ConversationCatalogCache(
        new CatalogOperationsReadCache(null) {
          @Override
          public List<CatalogRestClient.OperationDto> loadByModelId(String modelId) {
            return operations;
          }
        });
  }
}
