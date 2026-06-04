package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportCandidate;
import org.qubership.integration.platform.ai.chat.planning.ApiHubImportResult;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubDocumentPayload;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
  private ConversationPlanningDiaryService planningDiaryService;
  private ApiHubSpecificationImportService service;

  @BeforeEach
  void setUp() {
    catalogRestClient = mock(CatalogRestClient.class);
    catalogCache = mock(ConversationCatalogCache.class);
    catalogSpecificationImporter = mock(CatalogSpecificationImporter.class);
    apiHubMcpTools = mock(ApiHubMcpTools.class);
    planningDiaryService = mock(ConversationPlanningDiaryService.class);
    service =
        new ApiHubSpecificationImportService(
            catalogRestClient,
            catalogCache,
            catalogSpecificationImporter,
            apiHubMcpTools,
            planningDiaryService);
  }

  @Test
  void importCandidateReusesSystemAndImportsFullDocument() {
    ApiHubImportCandidate candidate =
        new ApiHubImportCandidate(
            "cand-1",
            Instant.now(),
            "Service Catalog Management",
            "INTERNAL",
            "S.ActProv.SvcCat",
            "2026.1@1",
            "api",
            "Service Catalog",
            "note");

    when(planningDiaryService.lastImportResult("conv-1")).thenReturn(Optional.empty());
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

    ApiHubSpecificationImportService.ImportOutcome outcome =
        service.importCandidate("conv-1", candidate);

    assertNotNull(outcome.result());
    assertEquals("sys-uuid", outcome.result().systemId());
    assertEquals("spec-1", outcome.result().specificationId());
    verify(planningDiaryService).recordImportResult(eq("conv-1"), any());
    verify(catalogRestClient, org.mockito.Mockito.never()).createSystem(any());
    verify(catalogRestClient)
        .createEnvironment(
            eq("sys-uuid"),
            eq(new CatalogCreateEnvironmentRequest("Service Catalog Management", "/")));
  }

  @Test
  void importCandidateCreatesDefaultEnvironmentWhenCreatingSystem() {
    ApiHubImportCandidate candidate =
        new ApiHubImportCandidate(
            "cand-2",
            Instant.now(),
            "Service Catalog Management",
            "INTERNAL",
            "S.ActProv.SvcCat",
            "2026.1@1",
            "api",
            "Service Catalog",
            "note");

    when(planningDiaryService.lastImportResult("conv-2")).thenReturn(Optional.empty());
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

    service.importCandidate("conv-2", candidate);

    verify(catalogRestClient)
        .createEnvironment(
            eq("new-sys"),
            eq(new CatalogCreateEnvironmentRequest("Service Catalog Management", "/")));
  }

  @Test
  void importCandidateReusesDiaryImportResultWithoutUpload() {
    ApiHubImportCandidate candidate =
        new ApiHubImportCandidate(
            "cand-1",
            Instant.now(),
            "Service Catalog Management",
            "INTERNAL",
            "S.ActProv.SvcCat",
            "2026.1@1",
            "api",
            "Service Catalog",
            "note");
    ApiHubImportResult existing =
        new ApiHubImportResult(
            "cand-1",
            Instant.now(),
            "sys-uuid",
            "spec-1",
            "group-1",
            "imp-1",
            "Service Catalog");
    when(planningDiaryService.lastImportResult("conv-1")).thenReturn(Optional.of(existing));

    ApiHubSpecificationImportService.ImportOutcome outcome =
        service.importCandidate("conv-1", candidate);

    assertEquals("spec-1", outcome.result().specificationId());
    verify(apiHubMcpTools, never()).fetchApiHubDocument(any(), any(), any(), any());
    verify(catalogSpecificationImporter, never()).importOpenApiDocument(any(), any(), any(), any(), any());
  }

  @Test
  void importCandidateReusesExistingCatalogSpecificationWithoutUpload() {
    ApiHubImportCandidate candidate =
        new ApiHubImportCandidate(
            "cand-3",
            Instant.now(),
            "Service Catalog Management",
            "INTERNAL",
            "S.ActProv.SvcCat",
            "2026.1@1",
            "api",
            "Service Catalog",
            "note");

    when(planningDiaryService.lastImportResult("conv-3")).thenReturn(Optional.empty());
    when(catalogRestClient.searchSystems(any()))
        .thenReturn(
            List.of(
                new CatalogRestClient.SystemDto(
                    "sys-uuid", "Service Catalog Management", "INTERNAL", null)));
    when(catalogRestClient.getEnvironments("sys-uuid")).thenReturn(List.of());
    when(catalogRestClient.getApiSpecifications("sys-uuid"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-existing",
                    "Service Catalog",
                    "group-existing",
                    "sys-uuid")));

    ApiHubSpecificationImportService.ImportOutcome outcome =
        service.importCandidate("conv-3", candidate);

    assertEquals("spec-existing", outcome.result().specificationId());
    verify(apiHubMcpTools, never()).fetchApiHubDocument(any(), any(), any(), any());
    verify(catalogSpecificationImporter, never()).importOpenApiDocument(any(), any(), any(), any(), any());
    verify(planningDiaryService).recordImportResult(eq("conv-3"), any());
  }
}
