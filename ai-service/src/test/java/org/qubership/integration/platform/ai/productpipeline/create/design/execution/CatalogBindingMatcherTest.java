package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;

@ExtendWith(MockitoExtension.class)
class CatalogBindingMatcherTest {

  @Mock CatalogSystemReadTool catalogReadTool;
  @InjectMocks CatalogBindingMatcher matcher;

  @Test
  void matchesServiceNameDespiteDashPunctuationMismatch() {
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto(
            "sys", "Stub OpenAPI Service", "EXTERNAL", "openapi");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec", "Stub OpenAPI Service v1", "group", "sys");
    CatalogRestClient.OperationDto op =
        new CatalogRestClient.OperationDto(
            "op-1", "stubOperation", "POST", "/stub/path", null);

    when(catalogReadTool.searchCatalogSystems("Stub OpenAPI Service"))
        .thenReturn(List.of(system));
    when(catalogReadTool.getApiSpecifications("sys")).thenReturn(List.of(spec));
    when(catalogReadTool.listCatalogOperations("spec", "sys", null)).thenReturn(List.of(op));

    CatalogBindingMatcher.MatchResult result =
        matcher.match("service-call", "Stub OpenAPI Service", "stubOperation");

    CatalogBindingMatcher.MatchResult.Exact exact =
        assertInstanceOf(CatalogBindingMatcher.MatchResult.Exact.class, result);
    assertEquals("op-1", exact.match().integrationOperationId());
  }

  @Test
  void matchesAsyncApiChannelOperation() {
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys", "Stub AsyncAPI", "INTERNAL", "async-api");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec", "Stub AsyncAPI v1", "group", "sys");
    CatalogRestClient.OperationDto op =
        new CatalogRestClient.OperationDto(
            "op-1", "stubAsyncOperation", "PUBLISH", "stub-channel", null);

    when(catalogReadTool.searchCatalogSystems("Stub AsyncAPI")).thenReturn(List.of(system));
    when(catalogReadTool.getApiSpecifications("sys")).thenReturn(List.of(spec));
    when(catalogReadTool.listCatalogOperations("spec", "sys", null))
        .thenReturn(List.of(op));

    CatalogBindingMatcher.MatchResult result =
        matcher.match("service-call", "Stub AsyncAPI", "PUBLISH stub-channel");

    CatalogBindingMatcher.MatchResult.Exact exact =
        assertInstanceOf(CatalogBindingMatcher.MatchResult.Exact.class, result);
    assertEquals("op-1", exact.match().integrationOperationId());
  }

  @Test
  void matchesAsyncApiOperationWhenQueryContainsNameAndChannel() {
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys", "Stub AsyncAPI Service", "EXTERNAL", "async-api");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec", "Stub AsyncAPI v1", "group", "sys");
    CatalogRestClient.OperationDto op =
        new CatalogRestClient.OperationDto(
            "op-1", "stubAsyncOperation", "SUBSCRIBE", "stub-channel", null);

    when(catalogReadTool.searchCatalogSystems("Stub AsyncAPI Service"))
        .thenReturn(List.of(system));
    when(catalogReadTool.getApiSpecifications("sys")).thenReturn(List.of(spec));
    when(catalogReadTool.listCatalogOperations("spec", "sys", null)).thenReturn(List.of(op));

    CatalogBindingMatcher.MatchResult result =
        matcher.match(
            "service-call", "Stub AsyncAPI Service", "stubAsyncOperation stub-channel");

    CatalogBindingMatcher.MatchResult.Exact exact =
        assertInstanceOf(CatalogBindingMatcher.MatchResult.Exact.class, result);
    assertEquals("op-1", exact.match().integrationOperationId());
  }

  @Test
  void listsOperationsByConversationIdInsteadOfMdc() {
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto(
            "sys", "om-order-lifecycle-manager-WFMS", "INTERNAL", "kafka");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec", "1.33", "group", "sys");
    CatalogRestClient.OperationDto op =
        new CatalogRestClient.OperationDto(
            "op-start", "onTaskStart", "PUBLISH", "env05-bss.task.start", null);

    when(catalogReadTool.searchCatalogSystems("om-order-lifecycle-manager-WFMS"))
        .thenReturn(List.of(system));
    when(catalogReadTool.getApiSpecifications("sys")).thenReturn(List.of(spec));
    when(catalogReadTool.listCatalogOperations("conv-1", "spec", "sys", null))
        .thenReturn(List.of(op));

    CatalogBindingMatcher.MatchResult result =
        matcher.match(
            "service-call", "om-order-lifecycle-manager-WFMS", "onTaskStart", "conv-1");

    CatalogBindingMatcher.MatchResult.Exact exact =
        assertInstanceOf(CatalogBindingMatcher.MatchResult.Exact.class, result);
    assertEquals("op-start", exact.match().integrationOperationId());
    verify(catalogReadTool).listCatalogOperations("conv-1", "spec", "sys", null);
    verify(catalogReadTool, never()).listCatalogOperations("spec", "sys", null);
  }
}
