package org.qubership.integration.platform.ai.integration.catalog.validation;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CatalogLookupArgsValidatorTest {

  private static final String CONV = "conv-validator-test";

  @Test
  void validateSpecificationIdForOperationsRejectsUuidWithoutSpecMarker() {
    var err =
        CatalogLookupArgsValidator.validateSpecificationIdForOperations(
            CatalogSystemToolNames.OPS, "364ea2f4-8918-4e47-9fc3-17652f1706d3", null, null);
    assertTrue(err.isPresent());
    assertTrue(err.get().message().contains("systemId"));
  }

  @Test
  void validateSpecificationIdForOperationsAcceptsOpaqueSpecIdWhenCacheEmpty() {
    assertFalse(
        CatalogLookupArgsValidator.validateSpecificationIdForOperations(
                CatalogSystemToolNames.OPS, "catalog-spec-id-7f3a9c2e1b", null, null)
            .isPresent());
  }

  @Test
  void validateSpecificationIdForOperationsAcceptsKnownIdFromCache() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    cache.rememberSpecificationsForSystem(
        CONV,
        "sys-1",
        List.of(new CatalogRestClient.SpecificationDto("spec-full-id", "1.0", "spec-group-id", null)));

    assertFalse(
        CatalogLookupArgsValidator.validateSpecificationIdForOperations(
                CatalogSystemToolNames.OPS, "spec-full-id", cache, CONV)
            .isPresent());
  }

  @Test
  void validateSpecificationIdForOperationsRejectsGroupIdFromCache() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    cache.rememberSpecificationsForSystem(
        CONV,
        "sys-1",
        List.of(new CatalogRestClient.SpecificationDto("spec-full-id", "1.0", "spec-group-id", null)));

    var err =
        CatalogLookupArgsValidator.validateSpecificationIdForOperations(
            CatalogSystemToolNames.OPS, "spec-group-id", cache, CONV);
    assertTrue(err.isPresent());
    assertTrue(err.get().message().contains("incomplete"));
    assertTrue(err.get().hint().contains("data[]"));
  }

  @Test
  void validateSpecificationIdForOperationsRejectsUnknownIdWhenCacheHasSpecs() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    cache.rememberSpecificationsForSystem(
        CONV,
        "sys-1",
        List.of(new CatalogRestClient.SpecificationDto("spec-full-id", "1.0", "spec-group-id", null)));

    var err =
        CatalogLookupArgsValidator.validateSpecificationIdForOperations(
            CatalogSystemToolNames.OPS, "other-spec-id", cache, CONV);
    assertTrue(err.isPresent());
    assertTrue(err.get().message().contains("not returned by getApiSpecifications"));
  }

  @Test
  void validateSpecificationIdForOperationsRejectsOperationName() {
    var err =
        CatalogLookupArgsValidator.validateSpecificationIdForOperations(
            CatalogSystemToolNames.OPS, "getPetById", null, null);
    assertTrue(err.isPresent());
    assertTrue(err.get().message().contains("operation name"));
    assertTrue(err.get().hint().contains("searchFilter"));
  }

  @Test
  void validateSystemIdForSpecificationsRejectsDesignDocumentSystemLabel() {
    var err =
        CatalogLookupArgsValidator.validateSystemIdForSpecifications(
            CatalogSystemToolNames.SPECS, "ACME_ORDERS");
    assertTrue(err.isPresent());
    assertTrue(err.get().message().contains("not a catalog UUID"));
    assertTrue(err.get().message().contains("design document"));
  }

  @Test
  void validateSystemIdForSpecificationsAcceptsCatalogUuid() {
    assertFalse(
        CatalogLookupArgsValidator.validateSystemIdForSpecifications(
                CatalogSystemToolNames.SPECS, "c7b258ed-2454-45b6-a548-e294e923ae50")
            .isPresent());
  }

  @Test
  void validateSystemIdForSpecificationsAcceptsNonUuidCatalogTestIds() {
    assertFalse(
        CatalogLookupArgsValidator.validateSystemIdForSpecifications(
                CatalogSystemToolNames.SPECS, "sys-1")
            .isPresent());
    assertFalse(
        CatalogLookupArgsValidator.validateSystemIdForSpecifications(
                CatalogSystemToolNames.SPECS, "s1")
            .isPresent());
  }

  @Test
  void emptySpecificationsHintAddsGuidance() {
    var hint =
        CatalogLookupArgsValidator.emptySpecificationsHint(
            "364ea2f4-8918-4e47-9fc3-17652f1706d3");
    assertTrue(hint.isPresent());
    assertTrue(hint.get().toLowerCase().contains("no specifications"));
    assertTrue(hint.get().contains("getApiSpecifications"));
  }

  @Test
  void emptySpecificationsHintForDesignDocumentSystemLabel() {
    var hint = CatalogLookupArgsValidator.emptySpecificationsHint("ACME_ORDERS");
    assertTrue(hint.isPresent());
    assertTrue(hint.get().contains("not a catalog UUID"));
    assertTrue(hint.get().contains("design document"));
  }
}
