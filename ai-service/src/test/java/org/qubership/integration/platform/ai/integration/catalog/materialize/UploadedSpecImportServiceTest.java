package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.attachment.SpecType;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.plan.UploadedSpecCandidate;
import org.qubership.integration.platform.ai.storage.S3Service;

class UploadedSpecImportServiceTest {

  @Test
  void importsNewUploadedSpec() {
    CatalogRestClient catalogRestClient = mock(CatalogRestClient.class);
    CatalogSpecificationImporter importer = mock(CatalogSpecificationImporter.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);
    S3Service s3Service = mock(S3Service.class);

    UploadedSpecImportService service =
        new UploadedSpecImportService(catalogRestClient, importer, cache, s3Service);

    UploadedSpecCandidate candidate =
        new UploadedSpecCandidate("uploads/order.json", "Order API", SpecType.OPENAPI);

    when(catalogRestClient.searchSystems(new CatalogSystemSearchRequest(candidate.title())))
        .thenReturn(List.of());
    when(catalogRestClient.createSystem(any(CatalogCreateSystemRequest.class)))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", candidate.title(), "INTERNAL", null));
    when(catalogRestClient.getEnvironments("sys-1")).thenReturn(List.of());
    when(catalogRestClient.getApiSpecifications("sys-1")).thenReturn(List.of());
    when(s3Service.readObjectBytes(candidate.s3Key())).thenReturn("{}".getBytes());
    when(importer.importOpenApiDocument(
            "sys-1", candidate.title(), null, "{}".getBytes(), "order.json"))
        .thenReturn(
            new CatalogSpecificationImporter.ImportOutcome("spec-1", "group-1", "import-1"));

    List<UploadedSpecImportResult> results =
        service.importCandidates("conv-1", List.of(candidate));

    assertEquals(1, results.size());
    assertEquals(candidate.s3Key(), results.get(0).s3Key());
    assertNotNull(results.get(0).binding());
    assertEquals("sys-1", results.get(0).binding().systemId());
    assertEquals("spec-1", results.get(0).binding().specificationId());
    assertEquals("group-1", results.get(0).binding().specificationGroupId());
    verify(importer, times(1)).importOpenApiDocument(any(), any(), any(), any(), any());
  }

  @Test
  void reusesExistingSpec() {
    CatalogRestClient catalogRestClient = mock(CatalogRestClient.class);
    CatalogSpecificationImporter importer = mock(CatalogSpecificationImporter.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);
    S3Service s3Service = mock(S3Service.class);

    UploadedSpecImportService service =
        new UploadedSpecImportService(catalogRestClient, importer, cache, s3Service);

    UploadedSpecCandidate candidate =
        new UploadedSpecCandidate("uploads/order.json", "Order API", SpecType.OPENAPI);

    when(catalogRestClient.searchSystems(new CatalogSystemSearchRequest(candidate.title())))
        .thenReturn(List.of(new CatalogRestClient.SystemDto("sys-1", candidate.title(), "INTERNAL", null)));
    when(catalogRestClient.getEnvironments("sys-1")).thenReturn(List.of());
    when(catalogRestClient.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-1", "v1", "sys-1-Order API", "sys-1")));

    List<UploadedSpecImportResult> results =
        service.importCandidates("conv-1", List.of(candidate));

    assertEquals(1, results.size());
    assertEquals("spec-1", results.get(0).binding().specificationId());
    verify(importer, never()).importOpenApiDocument(any(), any(), any(), any(), any());
  }
}
