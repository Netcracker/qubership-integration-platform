package org.qubership.integration.platform.ai.integration.apihub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApiHubSearchHitParserTest {

  @Test
  void parsesSingleClearHitFromOperationsArray() {
    String json =
        """
        {
          "operations": [
            {
              "operationId": "geographicSiteManagement-v4-geographicSite-_id_-get",
              "packageId": "S.CustParty.Care.GeoSite",
              "packageName": "Geographic Site",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Retrieve geographicSite by ID"
            }
          ]
        }
        """;

    ApiHubRequirementRefs refs =
        ApiHubSearchHitParser.parseSingleClearHit(json, "rest", "S.CustParty.Care.GeoSite");

    assertNotNull(refs);
    assertEquals("S.CustParty.Care.GeoSite", refs.packageId());
    assertEquals("2026.2@1", refs.version());
    assertEquals("geographicSiteManagement-v4-geographicSite-_id_-get", refs.operationId());
    assertEquals("api", refs.documentId());
  }

  @Test
  void returnsNullWhenMultipleDistinctPackages() {
    String json =
        """
        {
          "operations": [
            {
              "operationId": "op-a",
              "packageId": "S.A",
              "version": "2026.2@1",
              "documentId": "api"
            },
            {
              "operationId": "op-b",
              "packageId": "S.B",
              "version": "2026.2@1",
              "documentId": "api"
            }
          ]
        }
        """;

    assertNull(ApiHubSearchHitParser.parseSingleClearHit(json, "rest", null));
  }

  @Test
  void prefersPrimaryGetByIdWhenPackageScopedMultiHit() {
    String json =
        """
        {
          "items": [
            {
              "operationId": "geographicSiteManagement-v4-geographicSite-_id_-history-get",
              "packageId": "S.CustParty.Care.GeoSite",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Retrieve geographicSite state history"
            },
            {
              "operationId": "geographicSiteManagement-v4-geographicSite-_id_-get",
              "packageId": "S.CustParty.Care.GeoSite",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Retrieve geographicSite by ID"
            },
            {
              "operationId": "geographicSiteManagement-v4-geographicSite-_id_-relationship-get",
              "packageId": "S.CustParty.Care.GeoSite",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Retrieve geographicSite by ID (relationship)"
            }
          ]
        }
        """;

    assertNull(
        ApiHubSearchHitParser.parseSingleClearHit(json, "rest", "S.CustParty.Care.GeoSite"));
    ApiHubRequirementRefs preferred =
        ApiHubSearchHitParser.parsePreferredGetByIdHit(
            json, "rest", "S.CustParty.Care.GeoSite");
    assertNotNull(preferred);
    assertEquals(
        "geographicSiteManagement-v4-geographicSite-_id_-get", preferred.operationId());
  }

  @Test
  void prefersPrimaryGetByIdWhenMultiHitSharesPackageWithoutGroup() {
    String json =
        """
        {
          "items": [
            {
              "operationId": "partyManagement-v5-partyManagement-v5-hub-get",
              "packageId": "S.ProdCat.PartyMgmt",
              "packageName": "Party Management",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Discover hypermedia links for Party Management API"
            },
            {
              "operationId": "partyManagement-v5-partyManagement-v5-party-_id_-get",
              "packageId": "S.ProdCat.PartyMgmt",
              "packageName": "Party Management",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Retrieve party by ID"
            },
            {
              "operationId": "partyManagement-v5-partyManagement-v5-party-search-post",
              "packageId": "S.ProdCat.PartyMgmt",
              "packageName": "Party Management",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Search party by criteria"
            }
          ]
        }
        """;

    assertNull(ApiHubSearchHitParser.parseSingleClearHit(json, "rest", null));
    ApiHubRequirementRefs preferred =
        ApiHubSearchHitParser.parsePreferredGetByIdHit(json, "rest", null);
    assertNotNull(preferred);
    assertEquals("S.ProdCat.PartyMgmt", preferred.packageId());
    assertEquals("partyManagement-v5-partyManagement-v5-party-_id_-get", preferred.operationId());

    ApiHubRequirementRefs importCandidate =
        ApiHubSearchHitParser.parseImportCandidate(json, "rest", null);
    assertNotNull(importCandidate);
    assertEquals(preferred.operationId(), importCandidate.operationId());
  }

  @Test
  void fallsBackToPackageDocumentWhenSinglePackageHasNoPrimaryGetById() {
    String json =
        """
        {
          "items": [
            {
              "operationId": "partyManagement-v5-partyManagement-v5-hub-get",
              "packageId": "S.ProdCat.PartyMgmt",
              "packageName": "Party Management",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Discover hypermedia links for Party Management API"
            },
            {
              "operationId": "partyManagement-v5-partyManagement-v5-party-search-post",
              "packageId": "S.ProdCat.PartyMgmt",
              "packageName": "Party Management",
              "version": "2026.2@1",
              "documentId": "api",
              "title": "Search party by criteria"
            }
          ]
        }
        """;

    assertNull(ApiHubSearchHitParser.parsePreferredGetByIdHit(json, "rest", null));
    ApiHubRequirementRefs fallback =
        ApiHubSearchHitParser.parseSinglePackageDocumentFallback(json, "rest", null);
    assertNotNull(fallback);
    assertEquals("S.ProdCat.PartyMgmt", fallback.packageId());
    assertEquals("2026.2@1", fallback.version());
    assertNull(fallback.operationId());
    assertEquals("api", fallback.documentId());
  }
}
