package org.qubership.integration.platform.ai.chain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogDependency;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;

class ChainPlanGraphImporterTest {

  private ObjectMapper objectMapper;
  private CanonicalGraphDigest canonicalGraphDigest;
  private ChainPlanGraphImporter importer;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    canonicalGraphDigest = new CanonicalGraphDigest(objectMapper);
    importer = new ChainPlanGraphImporter(objectMapper, canonicalGraphDigest);
  }

  @Test
  void carriesChainNameAndDescription() {
    ImportedChainPlan imported = importer.importChain(facts(List.of(), List.of()));

    assertEquals("Order sync", imported.graph().chain().name());
    assertEquals("Syncs orders", imported.graph().chain().description());
  }

  @Test
  void mapsEachElementToANodeKeyedByItsCatalogId() {
    ChainCatalogFacts facts =
        facts(
            List.of(
                element("element-trigger", "http-trigger", "Receive order", null),
                element("element-script", "script", "Normalize payload", null)),
            List.of());

    ImportedChainPlan imported = importer.importChain(facts);

    ChainPlanNode trigger = node(imported.graph(), "element-trigger");
    assertEquals("http-trigger", trigger.type());
    assertEquals("Receive order", trigger.label());
    ChainPlanNode script = node(imported.graph(), "element-script");
    assertEquals("script", script.type());
    assertEquals("Normalize payload", script.label());
  }

  @Test
  void mapsContainmentToParentNodeId() {
    ChainCatalogFacts facts =
        facts(
            List.of(
                element("element-try-catch", "try-catch", "Guarded call", null),
                element("element-try", "try", "Try", "element-try-catch"),
                element("element-call", "service-call", "Call billing", "element-try")),
            List.of());

    ImportedChainPlan imported = importer.importChain(facts);

    assertNull(node(imported.graph(), "element-try-catch").parentNodeId());
    assertEquals("element-try-catch", node(imported.graph(), "element-try").parentNodeId());
    assertEquals("element-try", node(imported.graph(), "element-call").parentNodeId());
  }

  @Test
  void mapsDependenciesToEdges() {
    ChainCatalogFacts facts =
        facts(
            List.of(
                element("element-trigger", "http-trigger", "Receive order", null),
                element("element-script", "script", "Normalize payload", null)),
            List.of(new ChainCatalogDependency("element-trigger", "element-script")));

    ImportedChainPlan imported = importer.importChain(facts);

    assertEquals(1, imported.graph().edges().size());
    ChainPlanEdge edge = imported.graph().edges().get(0);
    assertEquals("element-trigger", edge.fromNodeId());
    assertEquals("element-script", edge.toNodeId());
    assertNull(edge.scopeNodeId());
    assertTrue(edge.edgeId() != null && !edge.edgeId().isBlank());
  }

  @Test
  void carriesPropertyValuesThatAreNotStrings() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("script", "return 200");
    properties.put("externalRoute", true);
    properties.put("connectTimeout", 30000);
    properties.put("headers", Map.of("accept", "application/json"));
    ChainCatalogFacts facts =
        facts(List.of(element("element-call", "service-call", "Call billing", null, properties)),
            List.of());

    ImportedChainPlan imported = importer.importChain(facts);

    ChainPlanNode call = node(imported.graph(), "element-call");
    assertEquals("return 200", property(call, "script"));
    assertEquals("true", property(call, "externalRoute"));
    assertEquals("30000", property(call, "connectTimeout"));
    assertEquals("{\"accept\":\"application/json\"}", property(call, "headers"));
  }

  @Test
  void keepsContainerPriorityAsAPropertyAndLeavesOrderUnset() {
    ChainCatalogFacts facts =
        facts(
            List.of(
                element(
                    "element-catch",
                    "catch",
                    "Catch",
                    "element-try-catch",
                    Map.of("priority", 2))),
            List.of());

    ImportedChainPlan imported = importer.importChain(facts);

    ChainPlanNode katch = node(imported.graph(), "element-catch");
    assertEquals("2", property(katch, "priority"));
    assertNull(katch.order());
  }

  @Test
  void bindsEveryNodeToItsCatalogElement() {
    ChainCatalogFacts facts =
        facts(
            List.of(
                element("element-trigger", "http-trigger", "Receive order", null),
                element("element-script", "script", "Normalize payload", null)),
            List.of());

    ImportedChainPlan imported = importer.importChain(facts);

    assertEquals("chain-1", imported.materializationMap().chainId());
    assertEquals(
        Map.of(
            "element-trigger", "element-trigger",
            "element-script", "element-script"),
        imported.materializationMap().nodeIdToElementId());
  }

  @Test
  void importsAChainWithNoElements() {
    ImportedChainPlan imported = importer.importChain(facts(List.of(), List.of()));

    assertTrue(imported.graph().nodes().isEmpty());
    assertTrue(imported.graph().edges().isEmpty());
    assertTrue(imported.materializationMap().nodeIdToElementId().isEmpty());
  }

  @Test
  void digestsTheSameChainAlikeWhateverOrderTheCatalogReturnsItIn() {
    List<ChainCatalogElement> elements =
        List.of(
            element("element-trigger", "http-trigger", "Receive order", null),
            element("element-script", "script", "Normalize payload", null));
    List<ChainCatalogDependency> dependencies =
        List.of(
            new ChainCatalogDependency("element-trigger", "element-script"),
            new ChainCatalogDependency("element-script", "element-sink"));

    ImportedChainPlan first = importer.importChain(facts(elements, dependencies));
    ImportedChainPlan shuffled =
        importer.importChain(facts(elements.reversed(), dependencies.reversed()));

    assertEquals(first.baseGraphDigest(), shuffled.baseGraphDigest());
  }

  @Test
  void digestsTheImportedGraphWithTheCanonicalDigest() {
    ChainCatalogFacts facts =
        facts(List.of(element("element-script", "script", "Normalize payload", null)), List.of());

    ImportedChainPlan imported = importer.importChain(facts);

    assertEquals(canonicalGraphDigest.sha256(imported.graph()), imported.baseGraphDigest());
  }

  private static ChainCatalogFacts facts(
      List<ChainCatalogElement> elements, List<ChainCatalogDependency> dependencies) {
    return new ChainCatalogFacts(
        "chain-1",
        "Order sync",
        "Syncs orders",
        elements.size(),
        dependencies.size(),
        "",
        elements,
        dependencies,
        "built_in_catalog");
  }

  private static ChainCatalogElement element(
      String elementId, String type, String name, String parentElementId) {
    return element(elementId, type, name, parentElementId, Map.of());
  }

  private static ChainCatalogElement element(
      String elementId,
      String type,
      String name,
      String parentElementId,
      Map<String, Object> properties) {
    return new ChainCatalogElement(elementId, type, name, parentElementId, properties);
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(candidate -> nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no node " + nodeId + " in imported graph"));
  }

  private static String property(ChainPlanNode node, String key) {
    return node.properties().stream()
        .filter(candidate -> key.equals(candidate.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no property " + key + " on node " + node.nodeId()));
  }
}
