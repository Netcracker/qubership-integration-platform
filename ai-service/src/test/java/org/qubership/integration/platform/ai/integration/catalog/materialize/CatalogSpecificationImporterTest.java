package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogImportRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.ImportSpecificationDto;
import org.qubership.integration.platform.ai.integration.catalog.client.SpecificationFileForm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogSpecificationImporterTest {

  @Test
  void comparesSpecificationsWithoutTheirDisplayTitle() {
    byte[] uploaded = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"New name\",\"version\":\"1.0\"},\"paths\":{}}".getBytes(StandardCharsets.UTF_8);
    String existing = "openapi: 3.0.3\ninfo:\n  title: Old name\n  version: '1.0'\npaths: {}\n";
    org.junit.jupiter.api.Assertions.assertTrue(CatalogSpecificationImporter.sameDocument(uploaded, existing));
    org.junit.jupiter.api.Assertions.assertFalse(CatalogSpecificationImporter.sameDocument(uploaded,
        existing.replace("'1.0'", "'2.0'")));
    org.junit.jupiter.api.Assertions.assertFalse(CatalogSpecificationImporter.sameDocument(uploaded,
        existing.replace("paths: {}", "paths: {/orders: {}}")));
  }

  private CatalogImportRestClient importRestClient;
  private CatalogRestClient catalogRestClient;
  private CatalogSpecificationImporter importer;

  @BeforeEach
  void setUp() {
    importRestClient = mock(CatalogImportRestClient.class);
    catalogRestClient = mock(CatalogRestClient.class);
    importer = new CatalogSpecificationImporter(importRestClient, catalogRestClient);
  }

  @Test
  void importOpenApiDocumentPostsMultipartAndPollsUntilDone() {
    byte[] payload = "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8);
    when(importRestClient.importSpecificationGroup(
            eq("sys-1"), eq("Service Catalog"), isNull(), any(SpecificationFileForm.class)))
        .thenReturn(new ImportSpecificationDto("imp-1", null, null, false, "group-1"));
    when(importRestClient.getImportStatus("imp-1"))
        .thenReturn(new ImportSpecificationDto("imp-1", null, null, true, "group-1"));
    when(catalogRestClient.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto("spec-1", "Service Catalog", "group-1", "sys-1")));
    when(catalogRestClient.getModelSource("spec-1")).thenReturn(new String(payload, StandardCharsets.UTF_8));

    CatalogSpecificationImporter.ImportOutcome outcome =
        importer.importOpenApiDocument("sys-1", "Service Catalog", null, payload, "openapi.json");

    assertEquals("spec-1", outcome.specificationId());
    assertEquals("group-1", outcome.specificationGroupId());
    assertEquals("imp-1", outcome.importId());
    verify(importRestClient)
        .importSpecificationGroup(
            eq("sys-1"), eq("Service Catalog"), isNull(), any(SpecificationFileForm.class));
    verify(importRestClient).getImportStatus("imp-1");
  }

  @Test
  void resolvesTheImportedSourceInsteadOfTheFirstVersionInTheGroup() {
    byte[] payload = "{\"openapi\":\"3.0.0\",\"info\":{\"version\":\"2\"}}".getBytes(StandardCharsets.UTF_8);
    when(importRestClient.importSpecification(eq("group-1"), any()))
        .thenReturn(new ImportSpecificationDto("imp-2", null, null, false, "group-1"));
    when(importRestClient.getImportStatus("imp-2"))
        .thenReturn(new ImportSpecificationDto("imp-2", null, null, true, "group-1"));
    when(catalogRestClient.getApiSpecifications("sys-1")).thenReturn(List.of(
        new CatalogRestClient.SpecificationDto("old", "API", "group-1", "sys-1"),
        new CatalogRestClient.SpecificationDto("new", "API", "group-1", "sys-1")));
    when(catalogRestClient.getModelSource("old")).thenReturn("{\"openapi\":\"3.0.0\",\"info\":{\"version\":\"1\"}}");
    when(catalogRestClient.getModelSource("new")).thenReturn("openapi: 3.0.0\ninfo:\n  version: '2'\n");

    assertEquals("new", importer.importOpenApiDocumentIntoGroup("sys-1", "group-1", payload, "api.json").specificationId());

    when(catalogRestClient.getModelSource("new")).thenReturn("{}");
    assertThrows(IllegalStateException.class,
        () -> importer.importOpenApiDocumentIntoGroup("sys-1", "group-1", payload, "api.json"));
  }
}
