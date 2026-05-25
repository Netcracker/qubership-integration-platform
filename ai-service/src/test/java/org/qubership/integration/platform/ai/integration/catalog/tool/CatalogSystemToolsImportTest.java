package org.qubership.integration.platform.ai.integration.catalog.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSystemToolsImportTest {

  @Test
  void validateImportApiHubArgsRejectsPlaceholderOperationId() {
    var err =
        CatalogSystemTools.validateImportApiHubArgsSpec(
            "pkg-1", "1.0.0", "operation-id-placeholder");
    assertTrue(err.isPresent());
    assertTrue(err.get().message().contains("placeholder"));
  }

  @Test
  void validateImportApiHubArgsAcceptsValidArgs() {
    assertFalse(
        CatalogSystemTools.validateImportApiHubArgsSpec(
                "pkg-1", "1.0.0", "real-operation-id-from-apihub")
            .isPresent());
  }

  @Test
  void validateImportApiHubArgsRejectsBlankPackageId() {
    var err = CatalogSystemTools.validateImportApiHubArgsSpec("  ", "1.0.0", "op-1");
    assertTrue(err.isPresent());
    assertTrue(err.get().message().contains("packageId"));
  }
}
