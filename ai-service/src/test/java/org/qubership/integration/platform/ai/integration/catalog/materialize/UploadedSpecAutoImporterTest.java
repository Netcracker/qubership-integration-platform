package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecAttachment;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.storage.S3Service;

class UploadedSpecAutoImporterTest {

  @Test
  void importsNewSpecWithoutTitleFallsBackToFilename() {
    S3Service s3 = mock(S3Service.class);
    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogSpecificationImporter importer = mock(CatalogSpecificationImporter.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);

    when(s3.readObjectBytes("key")).thenReturn("{}".getBytes());
    when(client.searchSystems(any())).thenReturn(List.of());
    when(client.createSystem(any(CatalogCreateSystemRequest.class)))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "orders-api", "INTERNAL", null));
    when(client.getEnvironments("sys-1")).thenReturn(List.of());
    when(client.getSpecificationGroups("sys-1")).thenReturn(List.of());
    when(client.createEnvironment(eq("sys-1"), any(CatalogCreateEnvironmentRequest.class)))
        .thenReturn(new CatalogRestClient.EnvironmentDto("env-1", "default", null));
    when(importer.importOpenApiDocument(
            eq("sys-1"), eq("orders-api"), isNull(), any(byte[].class), eq("orders-api.yaml")))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-1", "sg-1", "import-1"));

    UploadedSpecAutoImporter service = new UploadedSpecAutoImporter(s3, client, importer, cache);
    UploadedSpecImportOutcome outcome =
        service.importSpec("conv-1", new UploadedSpecAttachment("key", "orders-api.yaml"));

    assertEquals("key", outcome.s3Key());
    assertEquals("sys-1", outcome.systemId());
    assertEquals("spec-1", outcome.specificationId());
    assertEquals("sg-1", outcome.specificationGroupId());
    assertEquals(false, outcome.reused());
    verify(cache).rememberSystems(
        eq("conv-1"),
        eq(List.of(new CatalogRestClient.SystemDto("sys-1", "orders-api", "INTERNAL", null))));
    verify(cache).rememberActiveSystemId("conv-1", "sys-1");
    verify(cache).rememberSpecificationsForSystem(
        eq("conv-1"),
        eq("sys-1"),
        eq(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-1", "orders-api", "sg-1", "sys-1"))));
  }

  @Test
  void reusesExistingSpec() {
    S3Service s3 = mock(S3Service.class);
    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogSpecificationImporter importer = mock(CatalogSpecificationImporter.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);

    when(s3.readObjectBytes("existing-key")).thenReturn("{}".getBytes());
    when(client.searchSystems(any()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "orders-api", "INTERNAL", null)));
    when(client.getEnvironments("sys-1"))
        .thenReturn(List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", null)));
    when(client.getSpecificationGroups("sys-1"))
        .thenReturn(List.of(new CatalogRestClient.SpecificationGroupDto("sg-1", "orders-api")));
    when(client.getApiSpecifications("sys-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-1", "orders-api", "sg-1", "sys-1")));

    UploadedSpecAutoImporter service = new UploadedSpecAutoImporter(s3, client, importer, cache);
    UploadedSpecImportOutcome outcome =
        service.importSpec(
            "conv-2", new UploadedSpecAttachment("existing-key", "orders-api.yaml"));

    assertEquals("existing-key", outcome.s3Key());
    assertEquals("sys-1", outcome.systemId());
    assertEquals("spec-1", outcome.specificationId());
    assertEquals("sg-1", outcome.specificationGroupId());
    assertEquals(true, outcome.reused());
    verify(cache).rememberSystems(
        eq("conv-2"),
        eq(List.of(new CatalogRestClient.SystemDto("sys-1", "orders-api", "INTERNAL", null))));
    verify(cache).rememberActiveSystemId("conv-2", "sys-1");
    verify(cache).rememberSpecificationsForSystem(
        eq("conv-2"),
        eq("sys-1"),
        eq(
            List.of(
                new CatalogRestClient.SpecificationDto(
                    "spec-1", "orders-api", "sg-1", "sys-1"))));
    verify(importer, never()).importOpenApiDocument(any(), any(), any(), any(), any());
    verify(client, never()).createSystem(any());
    verify(client, never()).createEnvironment(any(), any());
  }

  @Test
  void importsIntoExistingGroupWhenGroupExistsButSpecMissing() {
    S3Service s3 = mock(S3Service.class);
    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogSpecificationImporter importer = mock(CatalogSpecificationImporter.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);

    when(s3.readObjectBytes("key"))
        .thenReturn("{\"info\":{\"title\":\"Order API\"}}".getBytes());
    when(client.searchSystems(any()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Order API", "INTERNAL", null)));
    when(client.getEnvironments("sys-1"))
        .thenReturn(List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", null)));
    when(client.getSpecificationGroups("sys-1"))
        .thenReturn(List.of(new CatalogRestClient.SpecificationGroupDto("sg-1", "Order API")));
    when(client.getApiSpecifications("sys-1")).thenReturn(List.of());
    when(importer.importOpenApiDocumentIntoGroup(
            eq("sys-1"), eq("sg-1"), any(byte[].class), eq("orders-api.yaml")))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-1", "sg-1", "import-1"));

    UploadedSpecAutoImporter service = new UploadedSpecAutoImporter(s3, client, importer, cache);
    UploadedSpecImportOutcome outcome =
        service.importSpec("conv-1", new UploadedSpecAttachment("key", "orders-api.yaml"));

    assertEquals("sys-1", outcome.systemId());
    assertEquals("spec-1", outcome.specificationId());
    assertEquals("sg-1", outcome.specificationGroupId());
    assertEquals(false, outcome.reused());
    verify(importer, never()).importOpenApiDocument(any(), any(), any(), any(), any());
    verify(client, never()).createSystem(any());
  }

  @Test
  void importsNewSpecWithTitle() {
    S3Service s3 = mock(S3Service.class);
    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogSpecificationImporter importer = mock(CatalogSpecificationImporter.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);

    when(s3.readObjectBytes("key"))
        .thenReturn("{\"info\":{\"title\":\"  Order\\tAPI  \"}}".getBytes());
    when(client.searchSystems(any())).thenReturn(List.of());
    when(client.createSystem(any(CatalogCreateSystemRequest.class)))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "Order API", "INTERNAL", null));
    when(client.getEnvironments("sys-1")).thenReturn(List.of());
    when(client.getSpecificationGroups("sys-1")).thenReturn(List.of());
    when(client.createEnvironment(eq("sys-1"), any(CatalogCreateEnvironmentRequest.class)))
        .thenReturn(new CatalogRestClient.EnvironmentDto("env-1", "default", null));
    when(importer.importOpenApiDocument(
            eq("sys-1"), eq("Order API"), isNull(), any(byte[].class), eq("orders-api.yaml")))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-1", "sg-1", "import-1"));

    UploadedSpecAutoImporter service = new UploadedSpecAutoImporter(s3, client, importer, cache);
    UploadedSpecImportOutcome outcome =
        service.importSpec("conv-1", new UploadedSpecAttachment("key", "orders-api.yaml"));

    assertEquals("key", outcome.s3Key());
    assertEquals("sys-1", outcome.systemId());
    assertEquals("spec-1", outcome.specificationId());
    assertEquals("sg-1", outcome.specificationGroupId());
    assertEquals(false, outcome.reused());
    verify(cache)
        .rememberSystems(
            eq("conv-1"),
            eq(List.of(new CatalogRestClient.SystemDto("sys-1", "Order API", "INTERNAL", null))));
    verify(cache).rememberActiveSystemId("conv-1", "sys-1");
    verify(cache)
        .rememberSpecificationsForSystem(
            eq("conv-1"),
            eq("sys-1"),
            eq(
                List.of(
                    new CatalogRestClient.SpecificationDto(
                        "spec-1", "Order API", "sg-1", "sys-1"))));
  }

  @Test
  void reusesExistingSystemWithTitleDerivedName() {
    S3Service s3 = mock(S3Service.class);
    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogSpecificationImporter importer = mock(CatalogSpecificationImporter.class);
    ConversationCatalogCache cache = mock(ConversationCatalogCache.class);

    when(s3.readObjectBytes("key"))
        .thenReturn("{\"info\":{\"title\":\"Order API\"}}".getBytes());
    when(client.searchSystems(argThat(req -> req != null && "Order API".equals(req.searchCondition()))))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Order API", "INTERNAL", null)));
    when(client.getEnvironments("sys-1"))
        .thenReturn(List.of(new CatalogRestClient.EnvironmentDto("env-1", "default", null)));
    when(client.getSpecificationGroups("sys-1")).thenReturn(List.of());
    when(client.getApiSpecifications("sys-1")).thenReturn(List.of());
    when(importer.importOpenApiDocument(
            eq("sys-1"), eq("Order API"), isNull(), any(byte[].class), eq("orders-api.yaml")))
        .thenReturn(new CatalogSpecificationImporter.ImportOutcome("spec-1", "sg-1", "import-1"));

    UploadedSpecAutoImporter service = new UploadedSpecAutoImporter(s3, client, importer, cache);
    UploadedSpecImportOutcome outcome =
        service.importSpec("conv-1", new UploadedSpecAttachment("key", "orders-api.yaml"));

    assertEquals("sys-1", outcome.systemId());
    assertEquals(false, outcome.reused());
    verify(client, never()).createSystem(any());
    verify(client).getSpecificationGroups("sys-1");
  }
}
