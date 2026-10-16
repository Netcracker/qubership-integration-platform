package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

/**
 * Permissive descriptor stubs so existing CREATE/EDIT tests stay green after whole-graph preflight.
 *
 * <p>Every type is a non-deprecated container with an empty {@code allowedChildren} map (any child
 * type is allowed). Tests that exercise a specific rule override the types they care about.
 */
public final class CatalogElementDescriptorTestSupport {

  private CatalogElementDescriptorTestSupport() {}

  public static void stubPermissive(CatalogElementDescriptorLoader loader) {
    lenient()
        .when(loader.load(anyString()))
        .thenAnswer(invocation -> permissive(invocation.getArgument(0)));
  }

  public static CatalogElementDescriptor permissive(String type) {
    return container(type, Map.of());
  }

  public static CatalogElementDescriptor container(
      String type, Map<String, CatalogChildQuantity> allowedChildren) {
    return new CatalogElementDescriptor(
        type, true, allowedChildren, List.of(), false, "priority", false, false, false, true);
  }

  public static CatalogElementDescriptor containerRequiringInner(String type) {
    return new CatalogElementDescriptor(
        type, true, Map.of(), List.of(), false, "priority", true, false, false, true);
  }

  public static CatalogElementDescriptor leaf(String type) {
    return new CatalogElementDescriptor(
        type, false, Map.of(), List.of(), false, "priority", false, false, false, true);
  }

  public static CatalogElementDescriptor leafRestrictedTo(String type, String... parentTypes) {
    return new CatalogElementDescriptor(
        type,
        false,
        Map.of(),
        List.of(parentTypes),
        false,
        "priority",
        false,
        false,
        false,
        true);
  }

  /**
   * Live-shaped trigger stub. Catalog trigger YAML omits {@code allowedInContainers}, so the DTO
   * default {@code true} wins. Nested-trigger preflight must use trigger-family membership, not
   * this flag.
   */
  public static CatalogElementDescriptor trigger(String type) {
    return new CatalogElementDescriptor(
        type, false, Map.of(), List.of(), false, "priority", false, false, false, true);
  }

  public static CatalogElementDescriptor deprecatedContainer(String type) {
    return new CatalogElementDescriptor(
        type, true, Map.of(), List.of(), false, "priority", false, true, false, true);
  }

  public static ChainPlanGraph graph(ChainPlanNode... nodes) {
    return new ChainPlanGraph(
        "1.0", new ChainSection("demo-chain", "Demo"), List.of(nodes), List.of());
  }

  public static ChainPlanNode node(String nodeId, String type, String parentNodeId) {
    return new ChainPlanNode(nodeId, type, nodeId, parentNodeId, null, List.of());
  }
}
