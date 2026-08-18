package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.container;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.containerRequiringInner;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.deprecatedContainer;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.graph;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.leaf;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.leafRestrictedTo;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.node;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.stubPermissive;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.trigger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ConnectionsApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer.PropertiesApplyResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanRemovalsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

class ChainPatchWriterPreflightTest {

  private ChainPlanPropertiesMaterializer propertiesMaterializer;
  private ChainPlanSkeletonMaterializer skeletonMaterializer;
  private ChainPlanConnectionsMaterializer connectionsMaterializer;
  private ChainPlanRemovalsMaterializer removalsMaterializer;
  private CatalogRestClient catalogRestClient;
  private CatalogElementDescriptorLoader descriptorLoader;
  private ChainPatchWriter writer;

  @BeforeEach
  void setUp() {
    propertiesMaterializer = mock(ChainPlanPropertiesMaterializer.class);
    skeletonMaterializer = mock(ChainPlanSkeletonMaterializer.class);
    connectionsMaterializer = mock(ChainPlanConnectionsMaterializer.class);
    removalsMaterializer = mock(ChainPlanRemovalsMaterializer.class);
    catalogRestClient = mock(CatalogRestClient.class);
    descriptorLoader = mock(CatalogElementDescriptorLoader.class);
    when(propertiesMaterializer.apply(any(), any()))
        .thenReturn(new PropertiesApplyResult(1, List.of(), null));
    when(connectionsMaterializer.apply(any(), any()))
        .thenReturn(new ConnectionsApplyResult(1, List.of()));
    stubPermissive(descriptorLoader);
    writer =
        new ChainPatchWriter(
            propertiesMaterializer,
            skeletonMaterializer,
            connectionsMaterializer,
            removalsMaterializer,
            catalogRestClient,
            descriptorLoader);
  }

  @Test
  void rejectsChildUnderNonContainer() {
    when(descriptorLoader.load("script")).thenReturn(leaf("script"));
    ChainPlanGraph desired =
        graph(node("parent-script", "script", null), node("child-script", "script", "parent-script"));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("parent-script"));

    assertRejected(result, "child-script", "parent-script", "not a container");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsChildTypeOutsideAllowedList() {
    when(descriptorLoader.load("box"))
        .thenReturn(container("box", Map.of("role", CatalogChildQuantity.ANY)));
    when(descriptorLoader.load("script")).thenReturn(leaf("script"));
    ChainPlanGraph desired =
        graph(node("parent-box", "box", null), node("child-script", "script", "parent-box"));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("parent-box"));

    assertRejected(result, "child-script", "script", "not allowed");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsParentRestrictionMismatch() {
    when(descriptorLoader.load("box")).thenReturn(container("box", Map.of()));
    when(descriptorLoader.load("role")).thenReturn(leafRestrictedTo("role", "try-2"));
    ChainPlanGraph desired =
        graph(node("parent-box", "box", null), node("child-role", "role", "parent-box"));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("parent-box"));

    assertRejected(result, "child-role", "box", "not permitted");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsCardinalityBelowMinimum() {
    when(descriptorLoader.load("fan"))
        .thenReturn(container("fan", Map.of("branch", CatalogChildQuantity.TWO_OR_MANY)));
    when(descriptorLoader.load("branch")).thenReturn(leaf("branch"));
    ChainPlanGraph desired =
        graph(node("fan-1", "fan", null), node("branch-1", "branch", "fan-1"));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("fan-1"));

    assertRejected(result, "fan-1", "branch", "minimum");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsCardinalityAboveMaximum() {
    when(descriptorLoader.load("box"))
        .thenReturn(container("box", Map.of("cap", CatalogChildQuantity.ONE)));
    when(descriptorLoader.load("cap")).thenReturn(leaf("cap"));
    ChainPlanGraph desired =
        graph(
            node("box-1", "box", null),
            node("cap-1", "cap", "box-1"),
            node("cap-2", "cap", "box-1"));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("box-1"));

    assertRejected(result, "box-1", "cap", "maximum");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsMissingMandatoryRole() {
    when(descriptorLoader.load("box"))
        .thenReturn(
            container(
                "box",
                Map.of(
                    "role", CatalogChildQuantity.ONE,
                    "cap", CatalogChildQuantity.ANY)));
    when(descriptorLoader.load("cap")).thenReturn(leaf("cap"));
    ChainPlanGraph desired = graph(node("box-1", "box", null), node("cap-1", "cap", "box-1"));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("box-1"));

    assertRejected(result, "box-1", "role", "missing mandatory");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsTriggerWithContainmentParent() {
    when(descriptorLoader.load("box")).thenReturn(container("box", Map.of()));
    when(descriptorLoader.load("http-trigger")).thenReturn(trigger("http-trigger"));
    ChainPlanGraph desired =
        graph(node("wrapper", "box", null), node("trigger", "http-trigger", "wrapper"));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("wrapper"));

    assertRejected(result, "trigger", "wrapper", "chain root");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsEmptyMandatoryInnerContentContainer() {
    when(descriptorLoader.load("shell")).thenReturn(containerRequiringInner("shell"));
    ChainPlanGraph desired = graph(node("shell-1", "shell", null));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("shell-1"));

    assertRejected(result, "shell-1", "shell", "inner content");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsNewlyIntroducedDeprecatedElement() {
    when(descriptorLoader.load("choice")).thenReturn(deprecatedContainer("choice"));
    when(descriptorLoader.load("script")).thenReturn(leaf("script"));
    ChainPlanGraph before = graph(node("script-1", "script", null));
    ChainPlanGraph desired =
        graph(node("script-1", "script", null), node("choice-1", "choice", null));

    ChainPatchWriteResult result =
        writer.write(
            new PatchedChain(before, desired, map(desired)),
            new GraphPatch(
                "patch-add-choice",
                "chain-patch",
                List.of(new NodePatch(GraphPatchOperation.ADD, node("choice-1", "choice", null), null)),
                List.of(),
                List.of(),
                null,
                List.of(),
                "adds a deprecated choice"));

    assertRejected(result, "choice-1", "choice", "deprecated");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsUnknownDescriptor() {
    when(descriptorLoader.load("unknown-type"))
        .thenThrow(new CatalogElementDescriptorException("unknown-type", "not found."));
    ChainPlanGraph desired = graph(node("n1", "unknown-type", null));

    ChainPatchWriteResult result = writer.write(mutating(desired), touch("n1"));

    assertRejected(result, "unknown-type", "not found");
    assertNoCatalogMutation();
  }

  @Test
  void preservesDeprecatedContainerAlreadyInCurrentChain() {
    when(descriptorLoader.load("choice")).thenReturn(deprecatedContainer("choice"));
    when(descriptorLoader.load("script")).thenReturn(leaf("script"));
    ChainPlanGraph current =
        graph(node("choice-1", "choice", null), node("script-1", "script", null));

    ChainPatchWriteResult result = writer.write(mutating(current), touch("script-1"));

    assertTrue(result.succeeded());
    verify(propertiesMaterializer).apply(any(), any());
  }

  private static PatchedChain mutating(ChainPlanGraph desired) {
    return new PatchedChain(desired, desired, map(desired));
  }

  private static MaterializationMap map(ChainPlanGraph graph) {
    Map<String, String> ids = new LinkedHashMap<>();
    for (var node : graph.nodes()) {
      ids.put(node.nodeId(), "catalog-" + node.nodeId());
    }
    return new MaterializationMap("chain-1", ids);
  }

  private static GraphPatch touch(String nodeId) {
    return new GraphPatch(
        "patch-touch",
        "chain-patch",
        List.of(),
        List.of(),
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE, nodeId, new PlanProperty("script", "return 1"))),
        null,
        List.of(),
        "touches one property so the writer does not treat the patch as empty");
  }

  private static void assertRejected(ChainPatchWriteResult result, String... fragments) {
    assertFalse(result.succeeded());
    String error = result.error();
    assertTrue(error != null && !error.isBlank(), "preflight must name the graph defect");
    for (String fragment : fragments) {
      assertTrue(error.contains(fragment), () -> "expected '" + fragment + "' in: " + error);
    }
  }

  private void assertNoCatalogMutation() {
    verify(skeletonMaterializer, never()).materializeElement(any(), any(), any(), any());
    verify(propertiesMaterializer, never()).apply(any(), any());
    verify(connectionsMaterializer, never()).apply(any(), any());
    verify(removalsMaterializer, never()).apply(any(), any(), any(), any());
    verify(catalogRestClient, never()).createElement(any(), any());
    verify(catalogRestClient, never()).updateElement(any(), any(), any());
    verify(catalogRestClient, never()).transferElements(any(), any());
    verify(catalogRestClient, never()).deleteElements(any(), any());
    verify(catalogRestClient, never()).createConnection(any(), any());
    verify(catalogRestClient, never()).deleteDependencies(any(), any());
    verify(catalogRestClient, never()).deleteChain(any());
  }
}
