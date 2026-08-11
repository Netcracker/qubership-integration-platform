package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubDocumentPayload;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiHubSpecificationImportServiceTest {

  private CatalogRestClient catalogRestClient;
  private ConversationCatalogCache catalogCache;
  private CatalogSpecificationImporter catalogSpecificationImporter;
  private ApiHubMcpTools apiHubMcpTools;
  private ApiHubSpecificationImportService service;

  @BeforeEach
  void setUp() {
    catalogRestClient = mock(CatalogRestClient.class);
    catalogCache = mock(ConversationCatalogCache.class);
    catalogSpecificationImporter = mock(CatalogSpecificationImporter.class);
    apiHubMcpTools = mock(ApiHubMcpTools.class);
    service =
        new ApiHubSpecificationImportService(
            catalogRestClient,
            catalogCache,
            catalogSpecificationImporter,
            apiHubMcpTools,
            new ObjectMapper());
  }

  @Test
  void importFromRefsReusesSystemAndImportsFullDocument() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ActProv.SvcCat",
            "2026.1@1",
            "op-get",
            "api",
            null,
            "Service Catalog Management",
            "Service Catalog");

    when(catalogRestClient.getApiSpecifications("sys-uuid")).thenReturn(List.of());
    when(catalogRestClient.searchSystems(any()))
        .thenReturn(
            List.of(
                new CatalogRestClient.SystemDto(
                    "sys-uuid", "Service Catalog Management", "INTERNAL", null)));
    when(catalogRestClient.getEnvironments("sys-uuid")).thenReturn(Collections.emptyList());
    when(catalogRestClient.createEnvironment(
            eq("sys-uuid"), eq(new CatalogCreateEnvironmentRequest("Service Catalog Management", "/"))))
        .thenReturn(new CatalogRestClient.EnvironmentDto("env-1", "Service Catalog Management", "/"));
    when(apiHubMcpTools.fetchApiHubDocument(
            eq("S.ActProv.SvcCat"), eq("2026.1@1"), eq("api"), eq("rest")))
        .thenReturn(
            new ApiHubDocumentPayload(
                "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8), "openapi.json"));
    when(catalogSpecificationImporter.importOpenApiDocument(
            eq("sys-uuid"),
            eq("Service Catalog"),
            isNull(),
            any(),
            eq("openapi.json")))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-1", "group-1", "imp-1"));
    when(apiHubMcpTools.fetchOperationOpenApiJson(
            eq("S.ActProv.SvcCat"), eq("2026.1@1"), eq("op-get"), eq("rest")))
        .thenReturn(
            "{\"paths\":{\"/items\":{\"get\":{}}}}".getBytes(StandardCharsets.UTF_8));
    when(catalogCache.refreshOperations("conv-1", "spec-1", "sys-uuid"))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto("op-catalog", "getItems", "GET", "/items", "spec-1")));

    ApiHubSpecificationImportResult result = service.importFromRefs("conv-1", refs);

    assertNotNull(result);
    assertEquals("sys-uuid", result.systemId());
    assertEquals("spec-1", result.specificationId());
    assertEquals(Optional.of("op-catalog"), result.catalogOperationId());
    verify(catalogRestClient, never()).createSystem(any());
    verify(catalogSpecificationImporter)
        .importOpenApiDocument(
            eq("sys-uuid"),
            eq("Service Catalog"),
            isNull(),
            any(),
            eq("openapi.json"));
  }

  @Test
  void importFromRefsCreatesDefaultEnvironmentWhenCreatingSystem() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ActProv.SvcCat",
            "2026.1@1",
            "op-get",
            "api",
            null,
            "Service Catalog Management",
            "Service Catalog");

    when(catalogRestClient.getApiSpecifications("new-sys")).thenReturn(List.of());
    when(catalogRestClient.searchSystems(any())).thenReturn(List.of());
    when(catalogRestClient.createSystem(new CatalogCreateSystemRequest("Service Catalog Management", "INTERNAL")))
        .thenReturn(
            new CatalogRestClient.SystemDto(
                "new-sys", "Service Catalog Management", "INTERNAL", null));
    when(catalogRestClient.getEnvironments("new-sys")).thenReturn(Collections.emptyList());
    when(catalogRestClient.createEnvironment(
            eq("new-sys"), eq(new CatalogCreateEnvironmentRequest("Service Catalog Management", "/"))))
        .thenReturn(new CatalogRestClient.EnvironmentDto("env-1", "Service Catalog Management", "/"));
    when(apiHubMcpTools.fetchApiHubDocument(
            eq("S.ActProv.SvcCat"), eq("2026.1@1"), eq("api"), eq("rest")))
        .thenReturn(
            new ApiHubDocumentPayload(
                "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8), "openapi.json"));
    when(catalogSpecificationImporter.importOpenApiDocument(
            eq("new-sys"),
            eq("Service Catalog"),
            isNull(),
            any(),
            eq("openapi.json")))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-2", "group-2", "imp-2"));
    when(apiHubMcpTools.fetchOperationOpenApiJson(any(), any(), any(), any()))
        .thenReturn("{\"paths\":{}}".getBytes(StandardCharsets.UTF_8));
    when(catalogCache.refreshOperations(any(), any(), any())).thenReturn(List.of());

    service.importFromRefs("conv-2", refs);

    verify(catalogRestClient)
        .createEnvironment(
            eq("new-sys"),
            eq(new CatalogCreateEnvironmentRequest("Service Catalog Management", "/")));
  }

  @Test
  void importFromRefsReusesExistingCatalogSpecificationWithoutUpload() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ActProv.SvcCat",
            "2026.2@1",
            "op-get",
            "api",
            null,
            null,
            null);

    when(catalogRestClient.searchSystems(any()))
        .thenReturn(
            List.of(
                new CatalogRestClient.SystemDto(
                    "sys-uuid", "S ActProv SvcCat", "INTERNAL", null)));
    when(catalogRestClient.getEnvironments("sys-uuid")).thenReturn(List.of());
    when(catalogRestClient.getApiSpecifications("sys-uuid"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-existing",
                    "v4.4",
                    "sys-uuid-2026.2@1",
                    "sys-uuid")));
    when(apiHubMcpTools.fetchOperationOpenApiJson(any(), any(), any(), any()))
        .thenReturn("{\"paths\":{}}".getBytes(StandardCharsets.UTF_8));
    when(catalogCache.refreshOperations("conv-3", "spec-existing", "sys-uuid"))
        .thenReturn(List.of());

    ApiHubSpecificationImportResult result = service.importFromRefs("conv-3", refs);

    assertEquals("spec-existing", result.specificationId());
    verify(apiHubMcpTools, never()).fetchApiHubDocument(any(), any(), any(), any());
    verify(catalogSpecificationImporter, never()).importOpenApiDocument(any(), any(), any(), any(), any());
  }

  @Test
  void belongsToSpecificationGroupMatchesGroupIdNotModelName() {
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto(
            "spec-1", "v4.4", "sys-uuid-2026.2@1", "sys-uuid");

    assertTrue(
        ApiHubSpecificationImportService.belongsToSpecificationGroup(
            spec, "sys-uuid", "2026.2@1"));
    assertFalse(
        ApiHubSpecificationImportService.belongsToSpecificationGroup(
            spec, "sys-uuid", "v4.4"));
  }
}
