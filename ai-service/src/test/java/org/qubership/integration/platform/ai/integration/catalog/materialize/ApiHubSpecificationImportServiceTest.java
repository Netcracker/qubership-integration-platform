package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubDocumentPayload;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogImportRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.ImportSpecificationDto;
import org.qubership.integration.platform.ai.integration.catalog.client.SpecificationFileForm;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
  private InternalAsyncMaasEnvironmentConfigurer maasConfigurer;
  private ApiHubSpecificationImportService service;

  @BeforeEach
  void setUp() {
    catalogRestClient = mock(CatalogRestClient.class);
    catalogCache = mock(ConversationCatalogCache.class);
    catalogSpecificationImporter = mock(CatalogSpecificationImporter.class);
    apiHubMcpTools = mock(ApiHubMcpTools.class);
    maasConfigurer = mock(InternalAsyncMaasEnvironmentConfigurer.class);
    service =
        new ApiHubSpecificationImportService(
            catalogRestClient,
            catalogCache,
            catalogSpecificationImporter,
            apiHubMcpTools,
            new ObjectMapper(),
            maasConfigurer);
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
    when(apiHubMcpTools.fetchApiHubDocument(any(), any(), any(), any()))
        .thenReturn(new ApiHubDocumentPayload("{}".getBytes(StandardCharsets.UTF_8), "api.json"));
    when(catalogRestClient.getModelSource("spec-existing")).thenReturn("{}");

    ApiHubSpecificationImportResult result = service.importFromRefs("conv-3", refs);

    assertEquals("spec-existing", result.specificationId());
    verify(apiHubMcpTools).fetchApiHubDocument("S.ActProv.SvcCat", "2026.2@1", "api", "rest");
    verify(catalogSpecificationImporter, never()).importOpenApiDocument(any(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @CsvSource({"v2, api", "v1, other-document"})
  void importsTheRequestedDocumentIntoTheExistingGroup(String version, String documentId) {
    var imports = mock(CatalogImportRestClient.class);
    service = new ApiHubSpecificationImportService(catalogRestClient, catalogCache,
        new CatalogSpecificationImporter(imports, catalogRestClient), apiHubMcpTools,
        new ObjectMapper(), maasConfigurer);
    var refs = new ApiHubRequirementRefs("pkg", version, "get-items", documentId, "rest", "Orders", "Shared");
    byte[] requested = """
        {
          "openapi": "3.0.0",
          "info": {"title": "Orders", "version": "2"},
          "paths": {"/items": {"get": {"responses": {"200": {
            "description": "ok",
            "content": {"application/json": {"schema": {"type": "integer"}}}
          }}}}}
        }
        """.getBytes(StandardCharsets.UTF_8);
    String previous = new String(requested, StandardCharsets.UTF_8)
        .replace("\"version\": \"2\"", "\"version\": \"1\"").replace("integer", "string");
    var oldSpec = new CatalogRestClient.SpecificationDto("old-v1", "Orders", "sys-Shared", "sys");
    var newSpec = new CatalogRestClient.SpecificationDto("requested", "Orders", "sys-Shared", "sys");
    when(catalogRestClient.searchSystems(any()))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys", "Orders", "EXTERNAL", null)));
    when(catalogRestClient.getApiSpecifications("sys"))
        .thenReturn(List.of(oldSpec), List.of(oldSpec, newSpec));
    when(catalogRestClient.getModelSource("old-v1")).thenReturn(previous);
    when(catalogRestClient.getModelSource("requested")).thenReturn(new String(requested, StandardCharsets.UTF_8));
    when(apiHubMcpTools.fetchApiHubDocument("pkg", version, documentId, "rest"))
        .thenReturn(new ApiHubDocumentPayload(requested, "api.json"));
    when(imports.importSpecification(eq("sys-Shared"), any())).thenAnswer(call -> {
      var form = call.getArgument(1, SpecificationFileForm.class);
      assertArrayEquals(requested, Files.readAllBytes(form.file.toPath()));
      return new ImportSpecificationDto("import-2", null, null, false, "sys-Shared");
    });
    when(imports.getImportStatus("import-2"))
        .thenReturn(new ImportSpecificationDto("import-2", null, null, true, "sys-Shared"));
    when(apiHubMcpTools.fetchOperationOpenApiJson("pkg", version, "get-items", "rest"))
        .thenReturn("{\"paths\":{\"/items\":{\"get\":{}}}}".getBytes(StandardCharsets.UTF_8));
    when(catalogCache.refreshOperations("regression", "requested", "sys"))
        .thenReturn(List.of(new CatalogRestClient.OperationDto("new-operation", "getItems", "GET", "/items", "requested")));
    when(catalogCache.refreshOperations("regression", "old-v1", "sys"))
        .thenReturn(List.of(new CatalogRestClient.OperationDto("old-operation", "getItems", "GET", "/items", "old-v1")));

    var result = service.importFromRefs("regression", refs);

    assertEquals("requested", result.specificationId());
    assertEquals(Optional.of("new-operation"), result.catalogOperationId());
    verify(apiHubMcpTools).fetchApiHubDocument("pkg", version, documentId, "rest");
    verify(imports).importSpecification(eq("sys-Shared"), any());
    verify(imports, never()).importSpecificationGroup(any(), any(), any(), any());
  }

  @Test
  void importFromRefsCreatesExternalSystemWithoutSlashEnvironment() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ActProv.SvcCat",
            "2026.1@1",
            "op-get",
            "api",
            null,
            "Service Catalog Management",
            "Service Catalog");
    when(catalogRestClient.searchSystems(any())).thenReturn(List.of());
    when(catalogRestClient.createSystem(
            new CatalogCreateSystemRequest("Service Catalog Management", "EXTERNAL")))
        .thenReturn(
            new CatalogRestClient.SystemDto(
                "new-sys", "Service Catalog Management", "EXTERNAL", null));
    when(catalogRestClient.getApiSpecifications("new-sys")).thenReturn(List.of());
    when(apiHubMcpTools.fetchApiHubDocument(any(), any(), any(), any()))
        .thenReturn(
            new ApiHubDocumentPayload(
                "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8), "openapi.json"));
    when(catalogSpecificationImporter.importOpenApiDocument(any(), any(), any(), any(), any()))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-2", "group-2", "imp-2"));
    when(apiHubMcpTools.fetchOperationOpenApiJson(any(), any(), any(), any()))
        .thenReturn("{\"paths\":{}}".getBytes(StandardCharsets.UTF_8));
    when(catalogCache.refreshOperations(any(), any(), any())).thenReturn(List.of());

    ApiHubSpecificationImportResult result =
        service.importFromRefs("conv-ext", refs, "EXTERNAL");

    assertEquals("EXTERNAL", result.systemType());
    verify(catalogRestClient)
        .createSystem(new CatalogCreateSystemRequest("Service Catalog Management", "EXTERNAL"));
    verify(catalogRestClient, never()).createEnvironment(any(), any());
  }

  @Test
  void importFromRefsReusesExistingSystemTypeWhenNameMatches() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ActProv.SvcCat", "2026.1@1", "op-get", "api", null, "Service Catalog Management",
            "Service Catalog");
    when(catalogRestClient.searchSystems(any()))
        .thenReturn(
            List.of(
                new CatalogRestClient.SystemDto(
                    "sys-uuid", "Service Catalog Management", "INTERNAL", null)));
    when(catalogRestClient.getEnvironments("sys-uuid")).thenReturn(List.of());
    when(catalogRestClient.getApiSpecifications("sys-uuid")).thenReturn(List.of());
    when(apiHubMcpTools.fetchApiHubDocument(any(), any(), any(), any()))
        .thenReturn(
            new ApiHubDocumentPayload("{}".getBytes(StandardCharsets.UTF_8), "openapi.json"));
    when(catalogSpecificationImporter.importOpenApiDocument(any(), any(), any(), any(), any()))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-1", "group-1", "imp-1"));
    when(apiHubMcpTools.fetchOperationOpenApiJson(any(), any(), any(), any()))
        .thenReturn("{\"paths\":{}}".getBytes(StandardCharsets.UTF_8));
    when(catalogCache.refreshOperations(any(), any(), any())).thenReturn(List.of());

    ApiHubSpecificationImportResult result =
        service.importFromRefs("conv-1", refs, "EXTERNAL");

    assertEquals("INTERNAL", result.systemType());
    verify(catalogRestClient, never()).createSystem(any());
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
