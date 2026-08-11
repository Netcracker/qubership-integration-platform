package org.qubership.integration.platform.ai.integration.apihub;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiHubRequirementRefsTest {

  @Test
  void parseAcceptsKeyValueWithoutSpace() {
    ApiHubRequirementRefs refs =
        ApiHubRequirementRefs.parse(
            List.of(
                "packageId:S.ActProv.SvcCat",
                "operationId:op-get",
                "version:2026.1@1",
                "documentId:api"));

    assertEquals("S.ActProv.SvcCat", refs.packageId());
    assertEquals("op-get", refs.operationId());
    assertEquals("2026.1@1", refs.version());
    assertEquals("api", refs.documentId());
    assertEquals("rest", refs.resolvedApiType());
    assertTrue(refs.hasImportableRefs());
  }

  @Test
  void parseAcceptsKeyValueWithSpaceAndDefaultsApiType() {
    ApiHubRequirementRefs refs =
        ApiHubRequirementRefs.parse(
            List.of(
                "packageId: S.ActProv.SvcCat",
                "operationId: op-get",
                "version: 2026.1@1"));

    assertEquals("S.ActProv.SvcCat", refs.packageId());
    assertEquals("op-get", refs.operationId());
    assertEquals("2026.1@1", refs.version());
    assertEquals("rest", refs.resolvedApiType());
    assertEquals("api", refs.documentSlug());
    assertTrue(refs.hasImportableRefs());
  }

  @Test
  void deriveReadableSystemNameFromPackageId() {
    ApiHubRequirementRefs refs =
        ApiHubRequirementRefs.parse(
            List.of("packageId:S.ActProv.SvcCat", "version:2026.1@1", "documentId:api"));

    assertEquals("S ActProv SvcCat", refs.catalogSystemName());
    assertEquals("2026.1@1", refs.specificationGroupName());
  }

  @Test
  void prefersPackageAndSpecificationNames() {
    ApiHubRequirementRefs refs =
        ApiHubRequirementRefs.parse(
            List.of(
                "packageId:S.ActProv.SvcCat",
                "version:2026.1@1",
                "documentId:api",
                "packageName: Service Catalog Management",
                "specificationName: Service Catalog"));

    assertEquals("Service Catalog Management", refs.catalogSystemName());
    assertEquals("Service Catalog", refs.specificationGroupName());
  }

  @Test
  void missingOperationAndDocumentIsNotImportable() {
    ApiHubRequirementRefs refs =
        ApiHubRequirementRefs.parse(List.of("packageId:S.ActProv.SvcCat", "version:2026.1@1"));

    assertFalse(refs.hasImportableRefs());
  }
}
