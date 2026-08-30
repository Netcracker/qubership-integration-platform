package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

@ExtendWith(MockitoExtension.class)
class CatalogBindingMatcherTest {

  @Mock CatalogSystemReadTool catalogReadTool;
  @InjectMocks CatalogBindingMatcher matcher;

  @Test
  void matchesServiceNameDespiteDashPunctuationMismatch() {
    NormalizedDesignFlow.Step step =
        new NormalizedDesignFlow.Step(
            "call-1",
            "service-call",
            "client",
            "p-stub-openapi-service",
            "stubOperation",
            "",
            List.of("f1"));
    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "test",
            "test",
            "",
            new NormalizedDesignFlow.Trigger(
                "http", "client", null, null, null, List.of("f1")),
            List.of(
                new NormalizedDesignFlow.Participant(
                    "client", "Client", "EXTERNAL", List.of("f1")),
                new NormalizedDesignFlow.Participant(
                    "p-stub-openapi-service",
                    "Stub OpenAPI Service",
                    "EXTERNAL",
                    List.of("f1"))),
            List.of(step),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

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

    CatalogBindingMatcher.MatchResult result = matcher.match(flow, step);

    CatalogBindingMatcher.MatchResult.Exact exact =
        assertInstanceOf(CatalogBindingMatcher.MatchResult.Exact.class, result);
    assertEquals("op-1", exact.match().integrationOperationId());
  }

  @Test
  void matchesAsyncApiChannelOperation() {
    NormalizedDesignFlow.Step step =
        new NormalizedDesignFlow.Step(
            "call-1",
            "service-call",
            "client",
            "svc",
            "PUBLISH stub-channel",
            "",
            List.of("f1"));
    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "test",
            "test",
            "",
            new NormalizedDesignFlow.Trigger(
                "http", "client", null, null, null, List.of("f1")),
            List.of(
                new NormalizedDesignFlow.Participant(
                    "client", "Client", "EXTERNAL", List.of("f1")),
                new NormalizedDesignFlow.Participant(
                    "svc", "Stub AsyncAPI", "INTERNAL", List.of("f1"))),
            List.of(step),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

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

    CatalogBindingMatcher.MatchResult result = matcher.match(flow, step);

    CatalogBindingMatcher.MatchResult.Exact exact =
        assertInstanceOf(CatalogBindingMatcher.MatchResult.Exact.class, result);
    assertEquals("op-1", exact.match().integrationOperationId());
  }

  @Test
  void matchesAsyncApiOperationWhenQueryContainsNameAndChannel() {
    NormalizedDesignFlow.Step step =
        new NormalizedDesignFlow.Step(
            "call-1",
            "service-call",
            "client",
            "svc",
            "stubAsyncOperation stub-channel",
            "",
            List.of("f1"));
    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "test",
            "test",
            "",
            new NormalizedDesignFlow.Trigger(
                "http", "client", null, null, null, List.of("f1")),
            List.of(
                new NormalizedDesignFlow.Participant(
                    "client", "Client", "EXTERNAL", List.of("f1")),
                new NormalizedDesignFlow.Participant(
                    "svc", "Stub AsyncAPI Service", "EXTERNAL", List.of("f1"))),
            List.of(step),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys", "Stub AsyncAPI Service", "EXTERNAL", "async-api");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec", "Stub AsyncAPI Service v1", "group", "sys");
    CatalogRestClient.OperationDto op =
        new CatalogRestClient.OperationDto(
            "op-1", "stubAsyncOperation", "SUBSCRIBE", "stub-channel", null);

    when(catalogReadTool.searchCatalogSystems("Stub AsyncAPI Service"))
        .thenReturn(List.of(system));
    when(catalogReadTool.getApiSpecifications("sys")).thenReturn(List.of(spec));
    when(catalogReadTool.listCatalogOperations("spec", "sys", null)).thenReturn(List.of(op));

    CatalogBindingMatcher.MatchResult result = matcher.match(flow, step);

    CatalogBindingMatcher.MatchResult.Exact exact =
        assertInstanceOf(CatalogBindingMatcher.MatchResult.Exact.class, result);
    assertEquals("op-1", exact.match().integrationOperationId());
  }
}
