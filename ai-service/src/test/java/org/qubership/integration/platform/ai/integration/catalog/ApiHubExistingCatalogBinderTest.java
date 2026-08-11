package org.qubership.integration.platform.ai.integration.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;

class ApiHubExistingCatalogBinderTest {

  @Test
  void searchTermsIncludePackageNameAndPackageIdSegments() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "op-search",
            null,
            "rest",
            "Party Management",
            null);
    List<String> terms = ApiHubExistingCatalogBinder.searchTerms(refs);
    assertTrue(terms.contains("Party Management"));
    assertTrue(terms.contains("PartyMgmt"));
    assertTrue(terms.contains("S ProdCat PartyMgmt"));
  }

  @Test
  void systemNameAgreesAcrossHumanAndImportDerivedNames() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "op-search",
            null,
            "rest",
            "Party Management",
            null);
    assertTrue(ApiHubExistingCatalogBinder.systemNameAgrees(refs, "S ProdCat PartyMgmt"));
    assertTrue(ApiHubExistingCatalogBinder.systemNameAgrees(refs, "Party Management"));
    assertFalse(ApiHubExistingCatalogBinder.systemNameAgrees(refs, "Payment Management"));
  }

  @Test
  void resolveBindsExistingHierarchy() {
    CatalogSystemReadTool read = mock(CatalogSystemReadTool.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);
    when(read.searchCatalogSystems(anyString()))
        .thenReturn(
            List.of(
                new CatalogRestClient.SystemDto(
                    "sys-1", "S ProdCat PartyMgmt", "INTERNAL", "http")));
    when(read.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-1", "v5.3", "sys-1-2026.2@1", "sys-1")));
    when(read.listCatalogOperations(eq("spec-1"), eq("sys-1"), isNull()))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "partyManagement-v5-partyManagement-v5-party-search-post",
                    "Search party by criteria",
                    "POST",
                    "/partyManagement/v5/partyManagement/v5/party/search",
                    "spec-1")));

    ApiHubExistingCatalogBinder binder = new ApiHubExistingCatalogBinder(read, cache);
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "partyManagement-v5-partyManagement-v5-party-search-post",
            null,
            "rest",
            "Party Management",
            null);

    Optional<ResolvedCatalogBinding> binding = binder.resolve("conv-1", refs);
    assertTrue(binding.isPresent());
    assertEquals("sys-1", binding.get().systemId());
    assertEquals("spec-1", binding.get().specificationId());
    assertEquals("sys-1-2026.2@1", binding.get().specificationGroupId());
    assertEquals(
        "partyManagement-v5-partyManagement-v5-party-search-post",
        binding.get().integrationOperationId());
  }
}
