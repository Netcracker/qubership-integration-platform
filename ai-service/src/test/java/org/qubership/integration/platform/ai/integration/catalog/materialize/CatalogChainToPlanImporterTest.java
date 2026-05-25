package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogChainToPlanImporterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private CatalogChainToPlanImporter importer() {
    return CatalogChainToPlanImporter.create(objectMapper);
  }

  @Test
  void singleRootNoDependencies() throws Exception {
    String json =
        """
{
  "name": "My chain",
  "elements": [
    { "id": "e1", "name": "Trigger", "type": "http-trigger", "properties": { "path": "/a" } }
  ]
}
""";

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertTrue(r.warnings().isEmpty());
    ChainImplementationPlan p = r.plan();
    assertEquals("My chain", p.getChain().getName());
    assertEquals(1, p.getElements().size());
    ElementPlan e = p.getElements().get(0);
    assertEquals("e1", e.getElementId());
    assertEquals("http-trigger-1", e.getClientId());
    assertEquals("http-trigger", e.getType());
    assertNull(e.getDisplayName());
    assertNull(e.getParentElementId());
    assertEquals(Map.of("path", "/a"), e.getExpectedProperties());
    assertNull(p.getConnections());
  }

  @Test
  void twoRootsDependencyGoesToPlanConnections() throws Exception {
    String json =
        """
        {
          "elements": [
            { "id": "a-a", "type": "http-trigger", "name": "A" },
            { "id": "b-b", "type": "http-sender", "name": "B" }
          ],
          "dependencies": [ { "from": "a-a", "to": "b-b" } ]
        }
        """;

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertTrue(r.warnings().isEmpty());
    assertNotNull(r.plan().getConnections());
    assertEquals(1, r.plan().getConnections().size());
    ConnectionPlan c = r.plan().getConnections().get(0);
    assertEquals("http-trigger-1", c.getFromClientId());
    assertEquals("http-sender-1", c.getToClientId());
  }

  @Test
  void nestedChildrenDependencyOnParentConnections() throws Exception {
    String json =
        """
        {
          "elements": [
            {
              "id": "parent",
              "type": "condition",
              "name": "If",
              "children": [
                { "id": "c1", "type": "script", "name": "S1", "parentElementId": "parent" },
                { "id": "c2", "type": "script", "name": "S2", "parentElementId": "parent" }
              ]
            }
          ],
          "dependencies": [ { "from": "c1", "to": "c2" } ]
        }
        """;

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertTrue(r.warnings().isEmpty());
    assertNull(r.plan().getConnections());
    ElementPlan parent = r.plan().getElements().get(0);
    assertEquals("parent", parent.getElementId());
    assertEquals(2, parent.getChildren().size());
    assertNotNull(parent.getConnections());
    assertEquals(1, parent.getConnections().size());
    assertEquals("script-c1", parent.getConnections().get(0).getFromClientId());
    assertEquals("script-c2", parent.getConnections().get(0).getToClientId());
  }

  @Test
  void differentParentsSkipsDependencyWithWarning() throws Exception {
    String json =
        """
        {
          "elements": [
            { "id": "r1", "type": "http-trigger", "name": "R1" },
            { "id": "r2", "type": "http-sender", "name": "R2" },
            { "id": "x", "type": "script", "name": "X", "parentElementId": "r1" }
          ],
          "dependencies": [ { "from": "x", "to": "r2" } ]
        }
        """;

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertTrue(r.warnings().stream().anyMatch(w -> w.contains("different parent")));
    assertNull(r.plan().getConnections());
  }

  @Test
  void duplicateElementIdUsesFirstOccurrence() throws Exception {
    String json =
        """
        {
          "elements": [
            { "id": "dup", "type": "http-trigger", "name": "First" },
            { "id": "dup", "type": "http-sender", "name": "Second" }
          ]
        }
        """;

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertEquals(1, r.plan().getElements().size());
    assertNull(r.plan().getElements().get(0).getDisplayName());
    assertTrue(r.warnings().stream().anyMatch(w -> w.contains("duplicate element id")));
  }

  @Test
  void missingParentPromotesToRootWithWarning() throws Exception {
    String json =
        """
        {
          "elements": [
            { "id": "orphan", "type": "script", "name": "O", "parentElementId": "missing" }
          ]
        }
        """;

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertEquals(1, r.plan().getElements().size());
    assertNull(r.plan().getElements().get(0).getParentElementId());
    assertTrue(r.warnings().stream().anyMatch(w -> w.contains("promoted to chain root")));
  }

  @Test
  void inferredParentWhenParentElementIdOmitted() throws Exception {
    String json =
        """
        {
          "elements": [
            {
              "id": "p1",
              "type": "condition",
              "name": "P",
              "children": [
                { "id": "c1", "type": "script", "name": "C" }
              ]
            }
          ]
        }
        """;

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertTrue(r.warnings().isEmpty());
    ElementPlan parent = r.plan().getElements().get(0);
    assertEquals(1, parent.getChildren().size());
    ElementPlan child = parent.getChildren().get(0);
    assertEquals("c1", child.getElementId());
    assertEquals("p1", child.getParentElementId());
  }

  @Test
  void explicitParentElementIdOverridesInferredNesting() throws Exception {
    String json =
        """
        {
          "elements": [
            {
              "id": "p1",
              "type": "condition",
              "name": "P",
              "children": [
                { "id": "c1", "type": "script", "name": "C", "parentElementId": "other" },
                { "id": "other", "type": "condition", "name": "O", "parentElementId": "p1" }
              ]
            }
          ]
        }
        """;

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importFromUiChainJson(json);
    assertTrue(r.warnings().isEmpty());
    ElementPlan p1 = r.plan().getElements().get(0);
    ElementPlan other = findById(p1, "other");
    ElementPlan c1 = findById(p1, "c1");
    assertNotNull(other);
    assertNotNull(c1);
    assertEquals("p1", other.getParentElementId());
    assertEquals("other", c1.getParentElementId());
  }

  private static ElementPlan findById(ElementPlan root, String elementId) {
    if (elementId.equals(root.getElementId())) {
      return root;
    }
    if (root.getChildren() == null) {
      return null;
    }
    for (ElementPlan c : root.getChildren()) {
      ElementPlan f = findById(c, elementId);
      if (f != null) {
        return f;
      }
    }
    return null;
  }

  @Test
  void propertiesCopiedToExpectedProperties() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("k", 1);
    CatalogChainToPlanImporter.UiChainRoot root = new CatalogChainToPlanImporter.UiChainRoot();
    CatalogChainToPlanImporter.UiElement el = new CatalogChainToPlanImporter.UiElement();
    el.id = "e1";
    el.type = "t";
    el.properties = props;
    root.elements = List.of(el);

    CatalogChainToPlanImporter.ChainPlanImportResult r = importer().importParsed(root);
    assertEquals(1, r.plan().getElements().get(0).getExpectedProperties().get("k"));
    props.put("k", 2);
    assertEquals(1, r.plan().getElements().get(0).getExpectedProperties().get("k"));
  }
}
