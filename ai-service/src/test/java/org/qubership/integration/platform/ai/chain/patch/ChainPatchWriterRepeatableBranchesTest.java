package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphReadBackVerifier;
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
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

@ExtendWith(MockitoExtension.class)
class ChainPatchWriterRepeatableBranchesTest {

  private static final String CHAIN_ID = "chain-1";

  @Mock private CatalogRestClient catalogRestClient;
  @Mock private CatalogElementDescriptorLoader descriptorLoader;
  @Mock private DeterministicElementSchemaService schemaService;
  @Mock private ChainPlanConnectionsMaterializer connectionsMaterializer;
  @Mock private ChainPlanRemovalsMaterializer removalsMaterializer;
  @Mock private CatalogGraphReadBackVerifier readBackVerifier;

  private ChainPlanSkeletonMaterializer skeletonMaterializer;
  private ChainPlanPropertiesMaterializer propertiesMaterializer;
  private ChainPatchWriter writer;

  @BeforeEach
  void setUp() {
    CatalogElementDescriptorTestSupport.stubPermissive(descriptorLoader);
    skeletonMaterializer = new ChainPlanSkeletonMaterializer(catalogRestClient, descriptorLoader);
    propertiesMaterializer =
        new ChainPlanPropertiesMaterializer(catalogRestClient, schemaService, new ObjectMapper());
    lenient()
        .when(schemaService.coercePatchPropertyValue(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    lenient()
        .when(removalsMaterializer.apply(any(), any(), any(), any()))
        .thenReturn(
            new ChainPlanRemovalsMaterializer.RemovalsApplyResult(
                List.of(), List.of(), List.of(), List.of(), null));
    lenient()
        .when(readBackVerifier.verify(any(), any(), any(), any(), any(), any()))
        .thenReturn(null);
    CatalogGraphMaterializer graphMaterializer =
        new CatalogGraphMaterializer(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalogRestClient,
            descriptorLoader,
            readBackVerifier);
    writer =
        new ChainPatchWriter(
            graphMaterializer,
            propertiesMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalogRestClient);
  }

  @Test
  void createsExtraCatchUnderExistingWrapper() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("catch-2")).thenReturn(Set.of());
    when(schemaService.validateElementPatch(eq("catch-2"), anyString()))
        .thenReturn("{\"valid\":true}");
    CatalogElementResponseDto wrapper = new CatalogElementResponseDto();
    wrapper.id = "el-tcff";
    wrapper.type = "try-catch-finally-2";
    CatalogElementResponseDto existingCatch = new CatalogElementResponseDto();
    existingCatch.id = "el-catch-1";
    existingCatch.type = "catch-2";
    existingCatch.parentElementId = "el-tcff";
    wrapper.children = List.of(existingCatch);
    when(catalogRestClient.getElement(CHAIN_ID, "el-tcff")).thenReturn(wrapper);
    when(catalogRestClient.getElement(CHAIN_ID, "el-catch-2")).thenReturn(new CatalogElementResponseDto());
    when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(List.of());
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("catch-2", "el-tcff", null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-catch-2", "catch-2", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("catch-1", "catch-2", "Catch 1", "tcff", null, List.of())),
            List.of());
    ChainPlanGraph after =
        new ChainPlanGraph(
            before.schemaVersion(),
            before.chain(),
            List.of(
                before.nodes().get(0),
                before.nodes().get(1),
                new ChainPlanNode("catch-2", "catch-2", "Catch 2", "tcff", null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap(CHAIN_ID, Map.of("tcff", "el-tcff", "catch-1", "el-catch-1"));
    GraphPatch patch =
        new GraphPatch(
            "patch-extra-catch",
            "chain-patch",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("catch-2", "catch-2", "Catch 2", "tcff", null, List.of()),
                    null)),
            List.of(),
            List.of(),
            null,
            List.of(),
            "adds a second catch branch");

    ChainPatchWriteResult result = writer.write(new PatchedChain(before, after, map), patch);

    assertTrue(result.succeeded());
    assertEquals("el-catch-2", result.materializationMap().nodeIdToElementId().get("catch-2"));
    assertEquals("el-catch-1", result.materializationMap().nodeIdToElementId().get("catch-1"));
    verify(catalogRestClient)
        .createElement(eq(CHAIN_ID), eq(new CatalogCreateElementRequest("catch-2", "el-tcff", null)));
  }

  @Test
  void extraIfKeepsItsOwnConditionAndPriority() throws Exception {
    when(schemaService.allowedPatchPropertyKeys("if")).thenReturn(Set.of("condition", "priority"));
    when(schemaService.validateElementPatch(eq("if"), anyString()))
        .thenReturn("{\"valid\":true}");
    when(catalogRestClient.getElement(CHAIN_ID, "el-cond")).thenReturn(new CatalogElementResponseDto());
    when(catalogRestClient.getElement(CHAIN_ID, "el-if-2")).thenReturn(new CatalogElementResponseDto());
    when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(List.of());
    when(catalogRestClient.createElement(
            eq(CHAIN_ID), eq(new CatalogCreateElementRequest("if", "el-cond", null))))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("el-if-2", "if", Map.of())),
                List.of(),
                List.of()));
    when(catalogRestClient.updateElement(anyString(), anyString(), anyMap()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(List.of(), List.of(), List.of()));

    ChainPlanGraph before =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("cond", "condition", "Condition", null, null, List.of()),
                new ChainPlanNode(
                    "if-1",
                    "if",
                    "If 1",
                    "cond",
                    null,
                    List.of(
                        new PlanProperty("condition", "${header.route} == 'a'"),
                        new PlanProperty("priority", "1")))),
            List.of());
    ChainPlanGraph after =
        new ChainPlanGraph(
            before.schemaVersion(),
            before.chain(),
            List.of(
                before.nodes().get(0),
                before.nodes().get(1),
                new ChainPlanNode(
                    "if-2",
                    "if",
                    "If 2",
                    "cond",
                    null,
                    List.of(
                        new PlanProperty("condition", "${header.route} == 'b'"),
                        new PlanProperty("priority", "2")))),
            List.of());
    MaterializationMap map =
        new MaterializationMap(CHAIN_ID, Map.of("cond", "el-cond", "if-1", "el-if-1"));
    GraphPatch patch =
        new GraphPatch(
            "patch-extra-if",
            "chain-patch",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    after.nodes().get(2),
                    null)),
            List.of(),
            List.of(),
            null,
            List.of(),
            "adds a second if branch with its own routing");

    ChainPatchWriteResult result = writer.write(new PatchedChain(before, after, map), patch);

    assertTrue(result.succeeded());
    assertEquals("el-if-2", result.materializationMap().nodeIdToElementId().get("if-2"));

    var order = inOrder(catalogRestClient);
    order
        .verify(catalogRestClient)
        .createElement(eq(CHAIN_ID), eq(new CatalogCreateElementRequest("if", "el-cond", null)));
    order.verify(catalogRestClient).updateElement(eq(CHAIN_ID), eq("el-if-2"), anyMap());
    verify(catalogRestClient, never()).updateElement(eq(CHAIN_ID), eq("el-if-1"), anyMap());

    ArgumentCaptor<Map<String, Object>> patchCaptor = ArgumentCaptor.forClass(Map.class);
    verify(catalogRestClient).updateElement(eq(CHAIN_ID), eq("el-if-2"), patchCaptor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> properties =
        (Map<String, Object>) patchCaptor.getValue().get("properties");
    assertEquals("${header.route} == 'b'", properties.get("condition"));
    assertEquals("2", properties.get("priority"));
  }

  @Test
  void doesNotCreatePerTypeJavaBranches() throws Exception {
    Path mainJava = Path.of("src/main/java");
    List<String> productionFiles =
        List.of(
            "org/qubership/integration/platform/ai/integration/catalog/materialize/ChainPlanSkeletonMaterializer.java",
            "org/qubership/integration/platform/ai/chain/patch/ChainPatchWriter.java");
    Set<String> repeatableBranchTypes =
        Set.of("if", "catch-2", "split-element-2", "async-split-element-2");

    for (String relativePath : productionFiles) {
      String source = Files.readString(mainJava.resolve(relativePath));
      for (String branchType : repeatableBranchTypes) {
        assertFalse(
            source.contains("switch (" + branchType) || source.contains("case \"" + branchType + "\""),
            () -> relativePath + " must not switch on repeatable branch type " + branchType);
        assertFalse(
            Stream.of(
                    "\"" + branchType + "\".equals(",
                    "equals(\"" + branchType + "\")",
                    "== \"" + branchType + "\"")
                .anyMatch(source::contains),
            () -> relativePath + " must not branch on repeatable branch type " + branchType);
      }
    }
  }
}
