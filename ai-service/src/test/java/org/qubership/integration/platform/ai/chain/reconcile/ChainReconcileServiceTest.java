package org.qubership.integration.platform.ai.chain.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogDependency;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult;

class ChainReconcileServiceTest {

  private ChainReconcileService service;

  @BeforeEach
  void setUp() {
    service = new ChainReconcileService();
  }

  @Test
  void reportsApprovedPropertyMismatch() {
    ChainPlanGraph plan = graphWithProperty("script-1", "script", "return 200");
    MaterializationMap map =
        new MaterializationMap("chain-1", Map.of("script-1", "catalog-script-1"));
    ChainCatalogFacts facts = factsWithProperty("catalog-script-1", "script", "return 500");

    ReconcileResult result = service.compare(plan, map, facts);

    assertFalse(result.matches());
    assertEquals(List.of("script-1.script"), result.propertyMismatches());
  }

  @Test
  void matchesPlanStringScalarsAgainstCatalogBooleansAndNumbers() {
    ChainPlanGraph plan =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", null),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1",
                    "http-trigger",
                    "Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("externalRoute", "false"),
                        new PlanProperty("connectTimeout", "120000")))),
            List.of());
    MaterializationMap map =
        new MaterializationMap("chain-1", Map.of("http-trigger-1", "el-trigger"));
    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-1",
            "demo",
            "",
            1,
            0,
            "",
            List.of(
                new ChainCatalogElement(
                    "el-trigger",
                    "http-trigger",
                    "Trigger",
                    null,
                    Map.<String, Object>of(
                        "externalRoute", Boolean.FALSE, "connectTimeout", Integer.valueOf(120000)))),
            List.of(),
            "built_in_catalog");

    ReconcileResult result = service.compare(plan, map, facts);

    assertTrue(result.matches());
    assertTrue(result.propertyMismatches().isEmpty());
  }

  @Test
  void reportsElementLabelMismatch() {
    ReconcileResult result = service.compare(planLabelled("Handler"), map(), factsLabelled("Script"));
    assertEquals(List.of("script-1"), result.labelMismatches());
  }

  @Test
  void matchesWhenPlanElementsAndDependenciesExist() {
    ChainPlanGraph plan =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", null),
            List.of(
                new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("n2", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("e1", "n1", "n2", null)));

    MaterializationMap map =
        new MaterializationMap("chain-1", Map.of("n1", "el-trigger", "n2", "el-script"));

    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-1",
            "demo",
            "",
            2,
            1,
            "",
            List.of(
                new ChainCatalogElement(
                    "el-trigger", "http-trigger", "Trigger", null, null, null, null, Map.of()),
                new ChainCatalogElement(
                    "el-script", "script", "Script", null, null, null, null, Map.of())),
            List.of(new ChainCatalogDependency("el-trigger", "el-script")),
            "built_in_catalog");

    ReconcileResult result = service.compare(plan, map, facts);

    assertTrue(result.matches());
  }

  @Test
  void reportsMissingNodeMapping() {
    ChainPlanGraph plan =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", null),
            List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());

    MaterializationMap map = new MaterializationMap("chain-1", Map.of());
    ChainCatalogFacts facts = emptyFacts("chain-1");

    ReconcileResult result = service.compare(plan, map, facts);

    assertFalse(result.matches());
    assertTrue(result.missingElementIds().contains("n1"));
  }

  @Test
  void skipsStructuralParentChildEdgesWhenMatchingConnections() {
    ChainPlanGraph plan =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", null),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("tcff", "try-catch-finally-2", "EH", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("script", "script", "Return ok", "try", null, List.of())),
            List.of(
                new ChainPlanEdge("e1", "trigger", "tcff", null),
                new ChainPlanEdge("e2", "try", "script", "tcff")));

    MaterializationMap map =
        new MaterializationMap(
            "chain-1",
            Map.of(
                "trigger", "el-trigger",
                "tcff", "el-tcff",
                "try", "el-try",
                "script", "el-script"));

    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-1",
            "demo",
            "",
            4,
            1,
            "",
            List.of(
                new ChainCatalogElement(
                    "el-trigger", "http-trigger", "Trigger", null, null, null, null, Map.of()),
                new ChainCatalogElement(
                    "el-tcff", "try-catch-finally-2", "EH", null, null, null, null, Map.of()),
                new ChainCatalogElement(
                    "el-try", "try-2", "Try", "el-tcff", null, null, null, Map.of()),
                new ChainCatalogElement(
                    "el-script", "script", "Return ok", "el-try", null, null, null, Map.of())),
            List.of(new ChainCatalogDependency("el-trigger", "el-tcff")),
            "built_in_catalog");

    ReconcileResult result = service.compare(plan, map, facts);

    assertTrue(result.matches());
    assertTrue(result.missingConnections().isEmpty());
  }

  @Test
  void reportsMissingDependency() {
    ChainPlanGraph plan =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", null),
            List.of(
                new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("n2", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("e1", "n1", "n2", null)));

    MaterializationMap map =
        new MaterializationMap("chain-1", Map.of("n1", "el-trigger", "n2", "el-script"));

    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-1",
            "demo",
            "",
            2,
            0,
            "",
            List.of(
                new ChainCatalogElement(
                    "el-trigger", "http-trigger", "Trigger", null, null, null, null, Map.of()),
                new ChainCatalogElement(
                    "el-script", "script", "Script", null, null, null, null, Map.of())),
            List.of(),
            "built_in_catalog");

    ReconcileResult result = service.compare(plan, map, facts);

    assertFalse(result.matches());
    assertTrue(result.missingConnections().contains("n1->n2"));
  }

  private static ChainCatalogFacts emptyFacts(String chainId) {
    return new ChainCatalogFacts(chainId, "demo", "", 0, 0, "", List.of(), List.of(), "built_in_catalog");
  }

  private static ChainPlanGraph graphWithProperty(String nodeId, String key, String value) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", null),
        List.of(
            new ChainPlanNode(
                nodeId, "script", "Script", null, null, List.of(new PlanProperty(key, value)))),
        List.of());
  }

  private static ChainCatalogFacts factsWithProperty(String elementId, String key, String value) {
    return new ChainCatalogFacts(
        "chain-1",
        "demo",
        "",
        1,
        0,
        "",
        List.of(
            new ChainCatalogElement(
                elementId, "script", "Script", null, Map.of(key, value))),
        List.of(),
        "built_in_catalog");
  }

  private static ChainPlanGraph planLabelled(String label) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", null),
        List.of(new ChainPlanNode("script-1", "script", label, null, null, List.of())),
        List.of());
  }

  private static ChainCatalogFacts factsLabelled(String label) {
    return new ChainCatalogFacts(
        "chain-1",
        "demo",
        "",
        1,
        0,
        "",
        List.of(new ChainCatalogElement("catalog-script-1", "script", label, null, Map.of())),
        List.of(),
        "built_in_catalog");
  }

  private static MaterializationMap map() {
    return new MaterializationMap("chain-1", Map.of("script-1", "catalog-script-1"));
  }
}
