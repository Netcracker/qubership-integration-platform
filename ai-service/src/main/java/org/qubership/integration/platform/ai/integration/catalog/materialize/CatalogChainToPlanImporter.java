package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ChainSection;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link ChainImplementationPlan} from UI-shaped chain JSON (elements tree + flat {@code
 * dependencies} with {@code from}/{@code to} referencing element ids). A JSON
 * <strong>array</strong> of elements (catalog list shape) is also accepted; chain name/description
 * are then absent unless wrapped in an object.
 *
 * <p>If {@code dependencies} is absent or empty, a single implicit root edge is added from the sole
 * {@code http-trigger} to the sole {@code condition} when both exist as chain roots (typical
 * catalog array export).
 *
 * <p>After import, {@link CatalogImportPlanPresentation#apply(ChainImplementationPlan)} rewrites
 * {@code clientId} values to a materialized-style naming (e.g. {@code http-trigger-1}, {@code
 * condition-1}, {@code if-even}, {@code script-greetings}), clears {@code displayName}, drops an
 * all-blank {@code chain} section, narrows {@code expectedProperties} for common element types, and
 * rewrites connection {@code clientId} references accordingly.
 *
 * <p>Explicit dependency edges are stored in {@link ChainImplementationPlan#connections} for root
 * siblings or in {@link ElementPlan#connections} for siblings under the same parent container when
 * both endpoints share the same resolved parent id. Otherwise the edge is skipped and a warning is
 * recorded.
 */
@ApplicationScoped
public class CatalogChainToPlanImporter {

  private final ObjectMapper objectMapper;

  @Inject
  public CatalogChainToPlanImporter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** For tests without CDI. */
  public static CatalogChainToPlanImporter create(ObjectMapper objectMapper) {
    return new CatalogChainToPlanImporter(objectMapper);
  }

  /**
   * @param uiChainJson chain object {@code { name, description, elements, dependencies }} as used
   *     by the UI, or a bare JSON <strong>array</strong> of elements (catalog export shape);
   *     unknown keys are ignored on parse
   */
  public ChainPlanImportResult importFromUiChainJson(String uiChainJson)
      throws JsonProcessingException {
    JsonNode tree = objectMapper.readTree(uiChainJson);
    UiChainRoot root;
    if (tree != null && tree.isArray()) {
      root = new UiChainRoot();
      root.elements = readElementsArray(tree);
    } else {
      root = objectMapper.treeToValue(tree, UiChainRoot.class);
    }
    return importParsed(root);
  }

  private List<UiElement> readElementsArray(JsonNode arrayNode) {
    List<UiElement> out = new ArrayList<>();
    if (arrayNode == null || !arrayNode.isArray()) {
      return out;
    }
    for (JsonNode n : arrayNode) {
      UiElement el = objectMapper.convertValue(n, UiElement.class);
      if (el != null) {
        out.add(el);
      }
    }
    return out;
  }

  ChainPlanImportResult importParsed(UiChainRoot root) {
    List<String> warnings = new ArrayList<>();
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setChain(null);
    if (root != null) {
      String nm = CatalogStrings.blankToNull(root.name);
      String dc = CatalogStrings.blankToNull(root.description);
      if (nm != null || dc != null) {
        plan.setChain(new ChainSection());
        plan.getChain().setName(nm);
        plan.getChain().setDescription(dc);
      }
    }

    List<UiElement> flat = new ArrayList<>();
    if (root != null && root.elements != null) {
      for (UiElement e : root.elements) {
        collectElementsDepthFirst(e, null, flat);
      }
    }
    dedupeById(flat, warnings);

    Map<String, ElementPlan> byCatalogId = new LinkedHashMap<>();
    for (UiElement src : flat) {
      if (src == null) {
        warnings.add("skipped null element in flattened list");
        continue;
      }
      String id = CatalogStrings.blankToNull(src.id);
      if (id == null) {
        warnings.add("skipped element with blank id");
        continue;
      }
      if (byCatalogId.containsKey(id)) {
        continue;
      }
      String explicitParent = CatalogStrings.blankToNull(src.parentElementId);
      String inferredParent = CatalogStrings.blankToNull(src.inferredParentElementId);
      String resolvedParent = explicitParent != null ? explicitParent : inferredParent;
      ElementPlan row = toElementPlan(src, id, resolvedParent);
      byCatalogId.put(id, row);
    }

    promoteOrphansMissingParent(byCatalogId, warnings);

    List<ElementPlan> roots = attachChildrenAndRoots(byCatalogId);
    plan.setElements(roots);

    if (root != null && root.dependencies != null && !root.dependencies.isEmpty()) {
      distributeDependencies(root.dependencies, byCatalogId, plan, warnings);
    } else {
      synthesizeRootTriggerToConditionConnection(plan, warnings);
    }

    CatalogImportPlanPresentation.apply(plan);

    return new ChainPlanImportResult(plan, List.copyOf(warnings));
  }

  /**
   * When the payload has no {@code dependencies} (typical catalog elements array), add a single
   * root-level edge from the sole {@code http-trigger} to the sole {@code condition} if both exist
   * as chain roots.
   */
  private static void synthesizeRootTriggerToConditionConnection(
      ChainImplementationPlan plan, List<String> warnings) {
    if (plan.getElements() == null || plan.getElements().isEmpty()) {
      return;
    }
    if (plan.getConnections() != null && !plan.getConnections().isEmpty()) {
      return;
    }
    List<ElementPlan> triggers = new ArrayList<>();
    List<ElementPlan> conditions = new ArrayList<>();
    for (ElementPlan r : plan.getElements()) {
      if (r == null) {
        continue;
      }
      if (CatalogStrings.blankToNull(r.getParentElementId()) != null) {
        continue;
      }
      if ("http-trigger".equals(r.getType())) {
        triggers.add(r);
      } else if ("condition".equals(r.getType())) {
        conditions.add(r);
      }
    }
    if (triggers.size() == 1 && conditions.size() == 1) {
      ConnectionPlan cp = new ConnectionPlan();
      cp.setFromClientId(triggers.get(0).getClientId());
      cp.setToClientId(conditions.get(0).getClientId());
      plan.setConnections(new ArrayList<>(List.of(cp)));
    } else if (triggers.size() > 1 || conditions.size() > 1) {
      warnings.add(
          "skipped implicit http-trigger -> condition connection "
              + "(expected exactly one root http-trigger and one root condition, found triggers="
              + triggers.size()
              + " conditions="
              + conditions.size()
              + ")");
    }
  }

  private static void collectElementsDepthFirst(
      UiElement node, String parentId, List<UiElement> out) {
    if (node == null) {
      return;
    }
    if (parentId != null) {
      node.inferredParentElementId = parentId;
    }
    out.add(node);
    String selfId = CatalogStrings.blankToNull(node.id);
    String nextParent = selfId != null ? selfId : parentId;
    if (node.children != null) {
      for (UiElement c : node.children) {
        collectElementsDepthFirst(c, nextParent, out);
      }
    }
  }

  private static void dedupeById(List<UiElement> flat, List<String> warnings) {
    Set<String> seen = new LinkedHashSet<>();
    List<UiElement> unique = new ArrayList<>();
    for (UiElement e : flat) {
      String id = CatalogStrings.blankToNull(e != null ? e.id : null);
      if (id == null) {
        continue;
      }
      if (!seen.add(id)) {
        warnings.add("duplicate element id in input JSON: " + id + " (using first occurrence)");
        continue;
      }
      unique.add(e);
    }
    flat.clear();
    flat.addAll(unique);
  }

  private static ElementPlan toElementPlan(
      UiElement src, String elementUuid, String resolvedParentElementId) {
    ElementPlan row = new ElementPlan();
    row.setElementId(elementUuid);
    row.setClientId(clientIdFor(elementUuid));
    row.setType(CatalogStrings.blankToNull(src.type));
    row.setDisplayName(CatalogStrings.blankToNull(src.name));
    row.setParentElementId(CatalogStrings.blankToNull(resolvedParentElementId));
    row.setExpectedProperties(copyProperties(src.properties));
    return row;
  }

  private static String clientIdFor(String catalogElementId) {
    return "ui-" + catalogElementId.replace('-', '_');
  }

  private static Map<String, Object> copyProperties(Map<String, Object> properties) {
    if (properties == null || properties.isEmpty()) {
      return new LinkedHashMap<>();
    }
    return new LinkedHashMap<>(properties);
  }

  private static void promoteOrphansMissingParent(
      Map<String, ElementPlan> byCatalogId, List<String> warnings) {
    for (ElementPlan row : byCatalogId.values()) {
      String pid = CatalogStrings.blankToNull(row.getParentElementId());
      if (pid != null && !byCatalogId.containsKey(pid)) {
        warnings.add(
            "element "
                + row.getElementId()
                + " references missing parentElementId="
                + pid
                + "; promoted to chain root");
        row.setParentElementId(null);
      }
    }
  }

  private static List<ElementPlan> attachChildrenAndRoots(Map<String, ElementPlan> byCatalogId) {
    Map<String, List<ElementPlan>> childrenByParent = new LinkedHashMap<>();
    for (ElementPlan row : byCatalogId.values()) {
      String parent = CatalogStrings.blankToNull(row.getParentElementId());
      childrenByParent
          .computeIfAbsent(parent != null ? parent : "", k -> new ArrayList<>())
          .add(row);
    }
    for (Map.Entry<String, List<ElementPlan>> e : childrenByParent.entrySet()) {
      String parentKey = e.getKey();
      ElementPlan parentRow = "".equals(parentKey) ? null : byCatalogId.get(parentKey);
      sortChildrenForParent(parentRow, e.getValue());
    }

    List<ElementPlan> roots = new ArrayList<>(childrenByParent.getOrDefault("", List.of()));
    for (ElementPlan parent : byCatalogId.values()) {
      String pid = parent.getElementId();
      List<ElementPlan> kids = childrenByParent.get(pid);
      if (kids != null && !kids.isEmpty()) {
        parent.setChildren(new ArrayList<>(kids));
        for (ElementPlan child : kids) {
          child.setParentElementId(pid);
        }
      }
    }

    roots.sort(CatalogChainToPlanImporter::rootElementOrder);
    return roots;
  }

  /**
   * Root order: {@code http-trigger} first, then {@code condition}, then others by {@code
   * elementId}.
   */
  private static int rootElementOrder(ElementPlan a, ElementPlan b) {
    int ra = rootRank(a);
    int rb = rootRank(b);
    if (ra != rb) {
      return Integer.compare(ra, rb);
    }
    String ida = a != null && a.getElementId() != null ? a.getElementId() : "";
    String idb = b != null && b.getElementId() != null ? b.getElementId() : "";
    return ida.compareTo(idb);
  }

  private static int rootRank(ElementPlan e) {
    if (e == null) {
      return 99;
    }
    if ("http-trigger".equals(e.getType())) {
      return 0;
    }
    if ("condition".equals(e.getType())) {
      return 1;
    }
    return 2;
  }

  private static void sortChildrenForParent(ElementPlan parent, List<ElementPlan> kids) {
    if (kids == null || kids.isEmpty()) {
      return;
    }
    if (parent != null && "condition".equals(parent.getType())) {
      kids.sort(
          Comparator.comparingInt(CatalogChainToPlanImporter::conditionBranchSortKey)
              .thenComparing(e -> e.getElementId() != null ? e.getElementId() : ""));
    } else {
      kids.sort(Comparator.comparing(e -> e.getElementId() != null ? e.getElementId() : ""));
    }
  }

  /** {@code else} last; {@code if} branches by {@code priority} ascending (missing → 0). */
  private static int conditionBranchSortKey(ElementPlan e) {
    if (e == null) {
      return 0;
    }
    if ("else".equals(e.getType())) {
      return Integer.MAX_VALUE;
    }
    if (e.getExpectedProperties() == null) {
      return 0;
    }
    Object p = e.getExpectedProperties().get("priority");
    if (p instanceof Number n) {
      return n.intValue();
    }
    if (p instanceof String s) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  private static void distributeDependencies(
      List<UiDependency> deps,
      Map<String, ElementPlan> byCatalogId,
      ChainImplementationPlan plan,
      List<String> warnings) {
    if (deps == null || deps.isEmpty()) {
      return;
    }
    for (UiDependency d : deps) {
      if (d == null) {
        continue;
      }
      String fromId = CatalogStrings.blankToNull(d.from);
      String toId = CatalogStrings.blankToNull(d.to);
      if (fromId == null || toId == null) {
        warnings.add("skipped dependency with blank from/to");
        continue;
      }
      ElementPlan from = byCatalogId.get(fromId);
      ElementPlan to = byCatalogId.get(toId);
      if (from == null || to == null) {
        warnings.add("skipped dependency " + fromId + " -> " + toId + " (unknown element id)");
        continue;
      }
      String pFrom = CatalogStrings.blankToNull(from.getParentElementId());
      String pTo = CatalogStrings.blankToNull(to.getParentElementId());
      if (!Objects.equals(pFrom, pTo)) {
        warnings.add(
            "skipped dependency "
                + fromId
                + " -> "
                + toId
                + " (endpoints have different parent: "
                + pFrom
                + " vs "
                + pTo
                + ")");
        continue;
      }
      ConnectionPlan cp = new ConnectionPlan();
      cp.setFromClientId(from.getClientId());
      cp.setToClientId(to.getClientId());
      if (pFrom == null) {
        if (plan.getConnections() == null) {
          plan.setConnections(new ArrayList<>());
        }
        plan.getConnections().add(cp);
      } else {
        ElementPlan parent = byCatalogId.get(pFrom);
        if (parent == null) {
          warnings.add(
              "skipped dependency "
                  + fromId
                  + " -> "
                  + toId
                  + " (parent container not found: "
                  + pFrom
                  + ")");
          continue;
        }
        if (parent.getConnections() == null) {
          parent.setConnections(new ArrayList<>());
        }
        parent.getConnections().add(cp);
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class UiChainRoot {
    public String id;
    public String name;
    public String description;
    public List<UiElement> elements;

    @JsonAlias({"dependencies"})
    public List<UiDependency> dependencies;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class UiElement {
    public String id;
    public String name;
    public String description;
    public String type;
    public String parentElementId;
    public Map<String, Object> properties;
    public List<UiElement> children;
    public String swimlaneId;
    public boolean mandatoryChecksPassed;

    /** Filled during flatten when parent is implied by JSON nesting; not part of UI JSON. */
    @JsonIgnore public String inferredParentElementId;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class UiDependency {
    public String id;
    public String from;
    public String to;
  }

  public record ChainPlanImportResult(ChainImplementationPlan plan, List<String> warnings) {}
}
