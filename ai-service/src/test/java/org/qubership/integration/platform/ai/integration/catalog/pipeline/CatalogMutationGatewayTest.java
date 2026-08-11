package org.qubership.integration.platform.ai.integration.catalog.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogChainPublicationService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

@ExtendWith(MockitoExtension.class)
class CatalogMutationGatewayTest {

  @Mock private ChainPlanSkeletonMaterializer skeletonMaterializer;
  @Mock private ChainPlanPropertiesMaterializer propertiesMaterializer;
  @Mock private ChainPlanConnectionsMaterializer connectionsMaterializer;
  @Mock private ApiHubSpecificationImportService apiHubSpecificationImportService;
  @Mock private CatalogChainPublicationService chainPublicationService;

  private CatalogMutationGateway gateway;

  @BeforeEach
  void setUp() {
    gateway =
        new CatalogMutationGateway(
            skeletonMaterializer,
            propertiesMaterializer,
            connectionsMaterializer,
            apiHubSpecificationImportService,
            chainPublicationService);
  }

  @Test
  void materializeSkeletonElementsDelegatesToSkeletonMaterializer() {
    ChainPlanGraph graph = validGraph();
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));
    when(skeletonMaterializer.materializeElements(graph, "chain-1")).thenReturn(map);

    MaterializationMap result =
        gateway.materializeSkeletonElements(graph, "chain-1").await().indefinitely();

    assertEquals(map, result);
    verify(skeletonMaterializer).materializeElements(graph, "chain-1");
  }

  @Test
  void materializeSkeletonElementDelegatesToSkeletonMaterializer() {
    ChainPlanGraph graph = validGraph();
    ChainPlanNode node = graph.nodes().get(0);
    MaterializationMap map = new MaterializationMap("chain-1", Map.of());
    when(skeletonMaterializer.materializeElement(graph, node, "chain-1", map)).thenReturn("el-1");

    String result =
        gateway.materializeSkeletonElement(graph, node, "chain-1", map).await().indefinitely();

    assertEquals("el-1", result);
    verify(skeletonMaterializer).materializeElement(graph, node, "chain-1", map);
  }

  @Test
  void listElementsDelegatesToSkeletonMaterializer() {
    CatalogElementResponseDto element = new CatalogElementResponseDto();
    element.id = "el-1";
    when(skeletonMaterializer.listElements("chain-1")).thenReturn(List.of(element));

    List<CatalogElementResponseDto> result = gateway.listElements("chain-1").await().indefinitely();

    assertEquals(1, result.size());
    assertSame(element, result.get(0));
    verify(skeletonMaterializer).listElements("chain-1");
  }

  @Test
  void applyPropertiesDelegatesToPropertiesMaterializer() {
    ChainPlanGraph graph = validGraph();
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));
    ChainPlanPropertiesMaterializer.PropertiesApplyResult expected =
        new ChainPlanPropertiesMaterializer.PropertiesApplyResult(1, List.of(), null);
    when(propertiesMaterializer.apply(graph, map)).thenReturn(expected);

    ChainPlanPropertiesMaterializer.PropertiesApplyResult result =
        gateway.applyProperties(graph, map).await().indefinitely();

    assertEquals(expected, result);
    verify(propertiesMaterializer).apply(graph, map);
  }

  @Test
  void applyConnectionsDelegatesToConnectionsMaterializer() {
    ChainPlanGraph graph = validGraph();
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"));
    ChainPlanConnectionsMaterializer.ConnectionsApplyResult expected =
        new ChainPlanConnectionsMaterializer.ConnectionsApplyResult(2, List.of());
    when(connectionsMaterializer.apply(graph, map)).thenReturn(expected);

    ChainPlanConnectionsMaterializer.ConnectionsApplyResult result =
        gateway.applyConnections(graph, map).await().indefinitely();

    assertEquals(expected, result);
    verify(connectionsMaterializer).apply(graph, map);
  }

  @Test
  void importApiHubSpecificationDelegatesToImportService() {
    ApiHubRequirementRefs refs =
        new ApiHubRequirementRefs(
            "S.ActProv.SvcCat", "2026.1@1", "op-get", "api", null, null, "Service Catalog");
    ApiHubSpecificationImportResult expected =
        new ApiHubSpecificationImportResult(
            "sys-1", "spec-1", "group-1", "imp-1", "Service Catalog", java.util.Optional.empty());
    when(apiHubSpecificationImportService.importFromRefs("conv-1", refs)).thenReturn(expected);

    ApiHubSpecificationImportResult result =
        gateway.importApiHubSpecification("conv-1", refs).await().indefinitely();

    assertEquals(expected, result);
    verify(apiHubSpecificationImportService).importFromRefs("conv-1", refs);
  }

  private static ChainPlanGraph validGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }
}
