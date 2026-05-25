package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorDefaultsApplicator;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorPatchValidator;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorResourceLoader;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingEnrichResult;
import org.qubership.integration.platform.ai.integration.catalog.binding.CatalogOperationBindingResolver;
import org.qubership.integration.platform.ai.integration.catalog.binding.OperationBindingMergeNormalizer;
import org.qubership.integration.platform.ai.integration.catalog.binding.ServiceCallBindingApplicator;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogElementReadCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogPatchPreparationServiceTest {

  private CatalogPatchPreparationService service;
  private CatalogOperationBindingResolver operationBindingResolver;
  private CatalogElementReadCache elementReadCache;
  private DeterministicElementSchemaService schemaService;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    elementReadCache = mock(CatalogElementReadCache.class);
    schemaService = mock(DeterministicElementSchemaService.class);
    operationBindingResolver = mock(CatalogOperationBindingResolver.class);
    OperationBindingMergeNormalizer mergeNormalizer =
        new OperationBindingMergeNormalizer(java.util.List.of(new ServiceCallBindingApplicator()));
    service =
        new CatalogPatchPreparationService(
            mock(CatalogRestClient.class),
            elementReadCache,
            schemaService,
            objectMapper,
            mock(CatalogDescriptorResourceLoader.class),
            mock(CatalogDescriptorDefaultsApplicator.class),
            mock(CatalogDescriptorPatchValidator.class),
            mergeNormalizer,
            operationBindingResolver);
    when(schemaService.allowedPatchPropertyKeys(anyString())).thenReturn(Set.of());
    when(schemaService.validateElementPatch(anyString(), anyString()))
        .thenReturn("{\"valid\":true}");
  }

  @Test
  void prepareUpdateElementBodyEnrichesPartialOperationBindingPatch() throws Exception {
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.type = "service-call";
    current.properties = new LinkedHashMap<>();
    when(elementReadCache.getElement("chain-1", "el-1")).thenReturn(current);

    Map<String, Object> enriched =
        Map.of(
            "integrationSpecificationId",
            "model-1",
            "integrationOperationId",
            "model-1-getPetById",
            "integrationOperationMethod",
            "GET",
            "integrationOperationPath",
            "/pet/{petId}",
            "integrationOperationProtocolType",
            "http");
    when(operationBindingResolver.enrichForProperties(any()))
        .thenReturn(CatalogOperationBindingEnrichResult.unchanged(enriched));

    String patchJson =
        """
        {"properties":{"integrationSpecificationId":"model-1","integrationOperationId":"model-1-getPetById"}}
        """;

    CatalogPatchPreparationService.PreparedUpdateBody prepared =
        service.prepareUpdateElementBody("chain-1", "el-1", patchJson);

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) prepared.body().get("properties");
    assertEquals("GET", props.get("integrationOperationMethod"));
    assertEquals("/pet/{petId}", props.get("integrationOperationPath"));
  }

  @Test
  void prepareUpdateElementBodyUnresolvedBindingUsesResolverReason() {
    CatalogElementResponseDto current = new CatalogElementResponseDto();
    current.type = "service-call";
    current.properties = new LinkedHashMap<>();
    when(elementReadCache.getElement("chain-1", "el-1")).thenReturn(current);
    when(operationBindingResolver.enrichForProperties(any()))
        .thenReturn(
            CatalogOperationBindingEnrichResult.unresolved(
                Map.of(), "integrationOperationId not found in catalog for specificationId=model-1"));

    String patchJson =
        """
        {"properties":{"integrationSpecificationId":"model-1","integrationOperationId":"missing"}}
        """;

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.prepareUpdateElementBody("chain-1", "el-1", patchJson));
    assertTrue(ex.getMessage().contains("not found in catalog"));
  }
}
