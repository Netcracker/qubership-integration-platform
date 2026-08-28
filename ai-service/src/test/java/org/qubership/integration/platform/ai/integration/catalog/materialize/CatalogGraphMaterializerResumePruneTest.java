package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** CREATE resume must prune optional generated children under already-mapped containers. */
class CatalogGraphMaterializerResumePruneTest {

  private CatalogGraphMaterializerTestHarness harness;

  @BeforeEach
  void setUp() {
    harness = new CatalogGraphMaterializerTestHarness();
    harness.catalog().setGeneratedChildDelivery(InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE);
  }

  @Test
  void resumePrunesLeftoverOptionalChildUnderSeededContainer() {
    ChainPlanGraph desired = CatalogGraphParityScenarios.conditionOneIf(false);
    String chainId = harness.chainId();

    String triggerId =
        harness.catalog().createSeededElement(chainId, "http-trigger", null, "trigger");
    String conditionId =
        harness.catalog().createSeededElement(chainId, "condition", null, "condition-1");

    CatalogElementResponseDto condition =
        harness.catalog().getElement(chainId, conditionId);
    String ifId = childId(condition, "if");
    String elseId = childId(condition, "else");

    Map<String, String> resumeMap = new LinkedHashMap<>();
    resumeMap.put("trigger", triggerId);
    resumeMap.put("condition-1", conditionId);
    resumeMap.put("if-1", ifId);

    CatalogGraphMaterializeResult result =
        harness
            .materializer()
            .apply(
                chainId,
                CatalogGraphMaterializer.emptyCurrent(desired),
                desired,
                new MaterializationMap(chainId, Map.copyOf(resumeMap), Map.of(), Map.of()));

    assertTrue(result.succeeded(), result.error());

    CatalogElementResponseDto conditionAfter =
        harness.catalog().getElement(chainId, conditionId);
    assertFalse(hasChildType(conditionAfter, "else"), "leftover else should be pruned");

    assertTrue(
        harness.catalog().listElements(chainId).stream()
            .filter(element -> "condition".equals(element.type))
            .count()
            == 1,
        "condition parent must not be created again");
  }

  private static String childId(CatalogElementResponseDto parent, String type) {
    if (parent.children == null) {
      throw new IllegalStateException("parent has no children");
    }
    for (CatalogElementResponseDto child : parent.children) {
      if (type.equals(child.type)) {
        return child.id;
      }
    }
    throw new IllegalStateException("no child of type " + type);
  }

  private static boolean hasChildType(CatalogElementResponseDto parent, String type) {
    if (parent.children == null) {
      return false;
    }
    return parent.children.stream().anyMatch(child -> type.equals(child.type));
  }
}
