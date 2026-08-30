package org.qubership.integration.platform.ai.integration.catalog.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializeResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecAutoImporter;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

@ExtendWith(MockitoExtension.class)
class CatalogMutationGatewayTest {

  @Mock private CatalogGraphMaterializer graphMaterializer;
  @Mock private ApiHubSpecificationImportService apiHubSpecificationImportService;
  @Mock private CatalogChainPublicationService chainPublicationService;
  @Mock private UploadedSpecAutoImporter uploadedSpecAutoImporter;

  private CatalogMutationGateway gateway;

  @BeforeEach
  void setUp() {
    gateway =
        new CatalogMutationGateway(
            graphMaterializer,
            apiHubSpecificationImportService,
            chainPublicationService,
            uploadedSpecAutoImporter);
  }

  @Test
  void applyGraphDelegatesToMaterializer() {
    ChainPlanGraph current = validGraph();
    ChainPlanGraph desired = validGraph();
    MaterializationMap map = new MaterializationMap("chain-1", Map.of("n1", "el-1"), Map.of(), Map.of());
    CatalogGraphMaterializeResult expected =
        new CatalogGraphMaterializeResult(
            map, List.of("n1"), List.of(), null, List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), false);
    when(graphMaterializer.apply("chain-1", current, desired, map)).thenReturn(expected);

    CatalogGraphMaterializeResult result =
        gateway.applyGraph(current, desired, map).await().indefinitely();

    assertEquals(expected, result);
    verify(graphMaterializer).apply("chain-1", current, desired, map);
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
