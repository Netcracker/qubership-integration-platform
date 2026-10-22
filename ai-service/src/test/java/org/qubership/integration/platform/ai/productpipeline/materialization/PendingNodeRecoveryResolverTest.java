package org.qubership.integration.platform.ai.productpipeline.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

class PendingNodeRecoveryResolverTest {

  private PendingNodeRecoveryResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new PendingNodeRecoveryResolver();
  }

  @Test
  void resolvesSingleExactCandidate() {
    ChainPlanNode pendingNode = pendingScript();
    MaterializationMap currentMap = new MaterializationMap("chain-1", Map.of("parent", "el-parent"), Map.of(), Map.of());
    CatalogElementResponseDto candidate = element("el-script-1", "script", "Parse payload", "el-parent");

    String resolved = resolver.resolve(pendingNode, List.of(candidate), currentMap);

    assertEquals("el-script-1", resolved);
  }

  @Test
  void returnsNullWhenNoCandidateMatches() {
    ChainPlanNode pendingNode = pendingScript();
    MaterializationMap currentMap = new MaterializationMap("chain-1", Map.of("parent", "el-parent"), Map.of(), Map.of());
    CatalogElementResponseDto differentLabel =
        element("el-script-1", "script", "Different label", "el-parent");

    String resolved = resolver.resolve(pendingNode, List.of(differentLabel), currentMap);

    assertNull(resolved);
  }

  @Test
  void failsClosedWhenPendingNodeHasTwoCandidates() {
    ChainPlanNode pendingNode = pendingScript();
    MaterializationMap currentMap = new MaterializationMap("chain-1", Map.of("parent", "el-parent"), Map.of(), Map.of());
    CatalogElementResponseDto first = element("el-script-1", "script", "Parse payload", "el-parent");
    CatalogElementResponseDto second = element("el-script-2", "script", "Parse payload", "el-parent");

    assertThrows(
        IllegalStateException.class,
        () -> resolver.resolve(pendingNode, List.of(first, second), currentMap));
  }

  @Test
  void ignoresAlreadyMappedCatalogElementIds() {
    ChainPlanNode pendingNode = pendingScript();
    MaterializationMap currentMap =
        new MaterializationMap("chain-1", Map.of("parent", "el-parent", "other", "el-script-1"), Map.of(), Map.of());
    CatalogElementResponseDto onlyKnownMatch =
        element("el-script-1", "script", "Parse payload", "el-parent");

    String resolved = resolver.resolve(pendingNode, List.of(onlyKnownMatch), currentMap);

    assertNull(resolved);
  }

  private static ChainPlanNode pendingScript() {
    return new ChainPlanNode("script-1", "script", "Parse payload", "parent", null, List.of());
  }

  private static CatalogElementResponseDto element(
      String id, String type, String label, String parentElementId) {
    CatalogElementResponseDto dto = new CatalogElementResponseDto();
    dto.id = id;
    dto.type = type;
    dto.name = label;
    dto.parentElementId = parentElementId;
    return dto;
  }
}
