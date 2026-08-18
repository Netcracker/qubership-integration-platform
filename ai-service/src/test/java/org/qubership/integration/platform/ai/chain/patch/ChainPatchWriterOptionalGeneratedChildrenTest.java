package org.qubership.integration.platform.ai.chain.patch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.stubPermissive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanRemovalsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

@ExtendWith(MockitoExtension.class)
class ChainPatchWriterOptionalGeneratedChildrenTest {

  private static final String CHAIN_ID = "chain-1";

  @Mock private CatalogRestClient catalogRestClient;
  @Mock private CatalogElementDescriptorLoader descriptorLoader;
  @Mock private DeterministicElementSchemaService schemaService;
  @Mock private ChainPlanConnectionsMaterializer connectionsMaterializer;
  @Mock private ChainPlanRemovalsMaterializer removalsMaterializer;

  private ChainPlanSkeletonMaterializer skeletonMaterializer;
  private ChainPlanPropertiesMaterializer propertiesMaterializer;
  private ChainPatchWriter writer;

  @BeforeEach
  void setUp() {
    stubPermissive(descriptorLoader);
    skeletonMaterializer = new ChainPlanSkeletonMaterializer(catalogRestClient, descriptorLoader);
    propertiesMaterializer =
        new ChainPlanPropertiesMaterializer(catalogRestClient, schemaService, new ObjectMapper());
    lenient()
        .when(schemaService.coercePatchPropertyValue(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    lenient()
        .when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of(), List.of(), List.of(), null));
    writer =
        new ChainPatchWriter(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalogRestClient,
            descriptorLoader);
    when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(List.of());
  }

  @Test
  void doesNotDeleteChildrenOfAPreexistingParent() {
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("script", "el-cond", null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-script", "script", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.getElement(CHAIN_ID, "el-cond")).thenReturn(liveConditionWithElse());
    when(catalogRestClient.getElement(CHAIN_ID, "el-script")).thenReturn(new CatalogElementResponseDto());

    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("cond", "condition", "Condition", null, null, List.of()),
                new ChainPlanNode("if-1", "if", "If", "cond", null, List.of())),
            List.of());
    ChainPlanGraph after =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("cond", "condition", "Condition", null, null, List.of()),
                new ChainPlanNode("if-1", "if", "If", "cond", null, List.of()),
                new ChainPlanNode("script", "script", "Script", "cond", null, List.of())),
            List.of());
    GraphPatch patch =
        new GraphPatch(
            "patch-add-script",
            "chain-patch",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("script", "script", "Script", "cond", null, List.of()),
                    null)),
            List.of(),
            List.of(),
            null,
            List.of(),
            "adds script under existing condition");

    writer.write(
        new PatchedChain(
            before, after, new MaterializationMap(CHAIN_ID, Map.of("cond", "el-cond", "if-1", "el-if"))),
        patch);

    verify(catalogRestClient, never()).deleteElements(any(), any());
  }

  private static CatalogElementResponseDto liveConditionWithElse() {
    CatalogElementResponseDto condition = new CatalogElementResponseDto();
    condition.id = "el-cond";
    condition.type = "condition";
    CatalogElementResponseDto ifBranch = new CatalogElementResponseDto();
    ifBranch.id = "el-if";
    ifBranch.type = "if";
    ifBranch.parentElementId = "el-cond";
    CatalogElementResponseDto elseBranch = new CatalogElementResponseDto();
    elseBranch.id = "el-else";
    elseBranch.type = "else";
    elseBranch.parentElementId = "el-cond";
    condition.children = List.of(ifBranch, elseBranch);
    return condition;
  }
}
