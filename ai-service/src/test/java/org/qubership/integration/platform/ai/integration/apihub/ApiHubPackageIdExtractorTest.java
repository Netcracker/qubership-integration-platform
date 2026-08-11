package org.qubership.integration.platform.ai.integration.apihub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApiHubPackageIdExtractorTest {

  @Test
  void extractsDottedPackageId() {
    assertEquals(
        "S.CustParty.Care.GeoSite",
        ApiHubPackageIdExtractor.extract(
            "Import OpenAPI from APIHub package S.CustParty.Care.GeoSite before design."));
  }

  @Test
  void returnsNullWhenMissing() {
    assertNull(ApiHubPackageIdExtractor.extract("Build a greeting chain with a script"));
  }
}
