package org.qubership.integration.platform.ai.integration.catalog.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

class CatalogElementWritesTest {

  @Test
  void patchKeepsDefaultsAndOverlaysOnlySuppliedProperties() {
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.name = "Trigger";
    current.description = "Existing trigger";
    current.properties = Map.of("accessControlType", "NONE", "contextPath", "/old");

    Map<String, Object> merged = CatalogElementWrites.mergeWithCurrentElement(
        current, Map.of("properties", Map.of("contextPath", "/new")));

    assertEquals("Trigger", merged.get("name"));
    assertEquals("Existing trigger", merged.get("description"));
    assertEquals(Map.of("accessControlType", "NONE", "contextPath", "/new"), merged.get("properties"));
    assertEquals("/old", current.properties.get("contextPath"));
  }

  @Test
  void createSelectsTheRequestedTypeInsteadOfGeneratedChildren() {
    var diff = new CatalogRestClient.ChainDiffDto(
        Arrays.asList(null,
            new CatalogRestClient.ElementSummaryDto("child", "catch", Map.of()),
            new CatalogRestClient.ElementSummaryDto("primary", " try-catch ", Map.of())),
        List.of(), List.of());

    assertEquals("primary", CatalogElementWrites.extractCreatedElementId(diff, "try-catch"));
    assertThrows(IllegalStateException.class,
        () -> CatalogElementWrites.extractCreatedElementId(diff, "script"));
  }
}
