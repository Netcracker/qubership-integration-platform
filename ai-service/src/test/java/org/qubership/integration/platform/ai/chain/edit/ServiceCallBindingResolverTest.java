package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class ServiceCallBindingResolverTest {

  @Test
  void resolveRoutesSingleCandidateThroughProjector() {
    CatalogRestClient catalogRestClient = mock(CatalogRestClient.class);
    CatalogSystemReadTool readTool = mock(CatalogSystemReadTool.class);

    when(catalogRestClient.getOperation("op-old"))
        .thenReturn(
            new CatalogRestClient.OperationDto("op-old", "Get order", "GET", "/orders", "spec-1"));
    when(catalogRestClient.getModel("spec-1"))
        .thenReturn(
            new CatalogRestClient.SpecificationDto("spec-1", "Orders API", "group-1", "sys-1"));
    when(catalogRestClient.getSystem("sys-1"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-1", "Petstore", "EXTERNAL", "http"));
    when(readTool.listCatalogOperations(eq("spec-1"), eq("sys-1"), eq("status")))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "op-status", "Post order status", "POST", "/orders/{id}/status", "spec-1")));

    ServiceCallBindingResolver resolver =
        new ServiceCallBindingResolver(catalogRestClient, readTool, null);

    ChainPlanNode target =
        new ChainPlanNode(
            "node-1",
            "service-call",
            "Call orders",
            null,
            null,
            List.of(
                new PlanProperty("serviceCallId", "call-occurrence-1"),
                new PlanProperty("integrationOperationId", "op-old")));

    ServiceCallBindingOutcome outcome = resolver.resolve(target, "status");

    ServiceCallBindingOutcome.Resolved resolved =
        assertInstanceOf(ServiceCallBindingOutcome.Resolved.class, outcome);
    ResolvedServiceCallBinding binding = resolved.binding();
    assertEquals("node-1", binding.targetNodeId());
    assertEquals("call-occurrence-1", binding.serviceCallId());
    assertEquals("EXTERNAL", binding.systemType());
    assertEquals("sys-1", binding.systemId());
    assertEquals("group-1", binding.specificationGroupId());
    assertEquals("spec-1", binding.specificationId());
    assertEquals("op-status", binding.operationId());
    assertEquals("http", binding.protocolType());
    assertEquals("POST", binding.method());
    assertEquals("/orders/{id}/status", binding.path());
    assertEquals("Post order status", binding.displayName());
    assertEquals(ResolvedServiceCallBinding.Source.EXISTING_CATALOG, binding.source());
    assertEquals("catalog:/v1/operations/op-status", binding.evidenceRef());
    assertEquals("", binding.packageId());
  }

  @Test
  void fromImportRoutesThroughProjectorWithApiHubSource() {
    CatalogRestClient catalogRestClient = mock(CatalogRestClient.class);
    when(catalogRestClient.getOperation("op-imported"))
        .thenReturn(
            new CatalogRestClient.OperationDto(
                "op-imported", "Get pets", "GET", "/pets", "spec-imported"));
    when(catalogRestClient.getSystem("sys-imported"))
        .thenReturn(
            new CatalogRestClient.SystemDto("sys-imported", "Petstore", "EXTERNAL", "http"));

    ServiceCallBindingResolver resolver =
        new ServiceCallBindingResolver(
            catalogRestClient, mock(CatalogSystemReadTool.class), null);

    ApiHubSpecificationImportResult result =
        new ApiHubSpecificationImportResult(
            "sys-imported",
            "spec-imported",
            "group-imported",
            "import-42",
            "Petstore API",
            Optional.of("op-imported"));

    ServiceCallBindingOutcome outcome =
        resolver.fromImport("imported-uuid", "call-petstore", result, "2024.4");

    ServiceCallBindingOutcome.Resolved resolved =
        assertInstanceOf(ServiceCallBindingOutcome.Resolved.class, outcome);
    ResolvedServiceCallBinding binding = resolved.binding();
    assertEquals("imported-uuid", binding.targetNodeId());
    assertEquals("call-petstore", binding.serviceCallId());
    assertEquals("EXTERNAL", binding.systemType());
    assertEquals("sys-imported", binding.systemId());
    assertEquals("group-imported", binding.specificationGroupId());
    assertEquals("spec-imported", binding.specificationId());
    assertEquals("op-imported", binding.operationId());
    assertEquals("http", binding.protocolType());
    assertEquals("GET", binding.method());
    assertEquals("/pets", binding.path());
    assertEquals("Get pets", binding.displayName());
    assertEquals(ResolvedServiceCallBinding.Source.APIHUB_IMPORT, binding.source());
    assertEquals("2024.4", binding.release());
    assertEquals("apihub-import:import-42", binding.evidenceRef());
    assertEquals("", binding.packageId());
  }
}
