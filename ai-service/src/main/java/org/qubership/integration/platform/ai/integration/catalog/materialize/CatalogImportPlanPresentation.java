package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes a freshly imported {@link ChainImplementationPlan} toward the post-catalog
 * materialized shape: semantic {@code clientId} values, {@code displayName} cleared, optional
 * {@code chain} cleared, narrowed {@code expectedProperties}, and connection {@code clientId}
 * references rewritten.
 */
public final class CatalogImportPlanPresentation {

  private static final Pattern SCRIPT_RETURN =
      Pattern.compile("return\\s+['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private CatalogImportPlanPresentation() {}

  public static void apply(ChainImplementationPlan plan) {
    if (plan == null) {
      return;
    }
    if (plan.getChain() != null) {
      boolean has =
          CatalogStrings.blankToNull(plan.getChain().getName()) != null
              || CatalogStrings.blankToNull(plan.getChain().getDescription()) != null;
      if (!has) {
        plan.setChain(null);
      }
    }
    Map<String, String> oldToNew = new LinkedHashMap<>();
    Set<String> taken = new HashSet<>();
    Map<String, Integer> rootTypeOrdinal = new HashMap<>();
    if (plan.getElements() != null) {
      for (ElementPlan root : plan.getElements()) {
        assignRecursive(root, null, true, rootTypeOrdinal, oldToNew, taken);
      }
    }
    rewriteConnections(plan.getConnections(), oldToNew);
    if (plan.getElements() != null) {
      for (ElementPlan root : plan.getElements()) {
        rewriteNestedConnections(root, oldToNew);
      }
    }
    clearFieldsAndNarrowProps(plan.getElements());
  }

  private static void assignRecursive(
      ElementPlan node,
      ElementPlan parent,
      boolean isRoot,
      Map<String, Integer> rootTypeOrdinal,
      Map<String, String> oldToNew,
      Set<String> taken) {
    if (node == null) {
      return;
    }
    String oldClient = node.getClientId() != null ? node.getClientId() : "";
    String neu = computeClientId(node, parent, isRoot, rootTypeOrdinal, taken);
    oldToNew.put(oldClient, neu);
    node.setClientId(neu);
    if (node.getChildren() != null) {
      for (ElementPlan ch : node.getChildren()) {
        assignRecursive(ch, node, false, rootTypeOrdinal, oldToNew, taken);
      }
    }
  }

  private static String computeClientId(
      ElementPlan node,
      ElementPlan parent,
      boolean isRoot,
      Map<String, Integer> rootTypeOrdinal,
      Set<String> taken) {
    if (isRoot) {
      String t = CatalogStrings.blankToNull(node.getType());
      if (t == null) {
        t = "element";
      }
      int n = rootTypeOrdinal.merge(t, 1, Integer::sum);
      return unique(t + "-" + n, taken);
    }
    if (parent != null && "condition".equals(parent.getType())) {
      if ("if".equals(node.getType())) {
        return unique(ifBranchClientId(node), taken);
      }
      if ("else".equals(node.getType())) {
        int n = rootTypeOrdinal.merge("__else_under_" + parent.getElementId(), 1, Integer::sum);
        return unique(n == 1 ? "else-default" : "else-" + n, taken);
      }
    }
    if (parent != null && "if".equals(parent.getType()) && "script".equals(node.getType())) {
      return unique(scriptClientId(node), taken);
    }
    if (parent != null && "else".equals(parent.getType()) && "script".equals(node.getType())) {
      return unique(scriptClientId(node), taken);
    }
    if ("script".equals(node.getType())) {
      return unique(scriptClientId(node), taken);
    }
    String t = CatalogStrings.blankToNull(node.getType());
    if (t == null) {
      t = "element";
    }
    String base = t + "-" + shortId(node.getElementId());
    return unique(base, taken);
  }

  private static String ifBranchClientId(ElementPlan ifRow) {
    String c = "";
    if (ifRow.getExpectedProperties() != null
        && ifRow.getExpectedProperties().get("condition") != null) {
      c = ifRow.getExpectedProperties().get("condition").toString();
    }
    String compact = c.replace(" ", "");
    if (compact.contains("%")
        && (compact.contains("%2") || compact.contains("%2==") || c.contains("% 2"))) {
      return "if-even";
    }
    if (!compact.contains("%") && compact.contains("==0")) {
      return "if-zero";
    }
    Object pr =
        ifRow.getExpectedProperties() != null
            ? ifRow.getExpectedProperties().get("priority")
            : null;
    return "if-p" + (pr != null ? pr : "0");
  }

  private static String scriptClientId(ElementPlan scriptRow) {
    String body = scriptBody(scriptRow);
    String slug = scriptReturnSlug(body);
    if (slug != null && !slug.isBlank()) {
      return "script-" + slug;
    }
    return "script-" + shortId(scriptRow.getElementId());
  }

  private static String scriptBody(ElementPlan scriptRow) {
    if (scriptRow.getExpectedProperties() == null
        || scriptRow.getExpectedProperties().get("script") == null) {
      return "";
    }
    return scriptRow.getExpectedProperties().get("script").toString();
  }

  private static String scriptReturnSlug(String scriptBody) {
    if (scriptBody == null || scriptBody.isBlank()) {
      return null;
    }
    Matcher m = SCRIPT_RETURN.matcher(scriptBody.trim());
    if (!m.find()) {
      return null;
    }
    String literal = m.group(1).trim();
    return slugifyLiteral(literal);
  }

  private static String slugifyLiteral(String literal) {
    String lower = literal.toLowerCase(Locale.ROOT);
    String t = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return t.isBlank() ? null : t;
  }

  private static String shortId(String rawId) {
    if (rawId == null || rawId.isBlank()) {
      return "id";
    }
    String s = rawId.replace("-", "");
    return s.length() <= 8 ? s : s.substring(0, 8);
  }

  private static String unique(String base, Set<String> taken) {
    String candidate = base;
    int i = 2;
    while (!taken.add(candidate)) {
      candidate = base + "-" + i;
      i++;
    }
    return candidate;
  }

  private static void rewriteConnections(List<ConnectionPlan> list, Map<String, String> oldToNew) {
    if (list == null) {
      return;
    }
    for (ConnectionPlan cp : list) {
      if (cp == null) {
        continue;
      }
      cp.setFromClientId(remap(cp.getFromClientId(), oldToNew));
      cp.setToClientId(remap(cp.getToClientId(), oldToNew));
    }
  }

  private static String remap(String id, Map<String, String> oldToNew) {
    String k = CatalogStrings.blankToNull(id);
    if (k == null) {
      return id;
    }
    return oldToNew.getOrDefault(k, k);
  }

  private static void rewriteNestedConnections(ElementPlan node, Map<String, String> oldToNew) {
    if (node == null) {
      return;
    }
    rewriteConnections(node.getConnections(), oldToNew);
    if (node.getChildren() != null) {
      for (ElementPlan ch : node.getChildren()) {
        rewriteNestedConnections(ch, oldToNew);
      }
    }
  }

  private static void clearFieldsAndNarrowProps(List<ElementPlan> roots) {
    if (roots == null) {
      return;
    }
    for (ElementPlan r : roots) {
      clearRecursive(r);
    }
  }

  private static void clearRecursive(ElementPlan node) {
    if (node == null) {
      return;
    }
    node.setDisplayName(null);
    narrowProps(node);
    if (node.getChildren() != null) {
      for (ElementPlan ch : node.getChildren()) {
        clearRecursive(ch);
      }
    }
  }

  private static void narrowProps(ElementPlan node) {
    String t = node.getType();
    Map<String, Object> src = node.getExpectedProperties();
    if (src == null) {
      node.setExpectedProperties(new LinkedHashMap<>());
      return;
    }
    if ("http-trigger".equals(t)) {
      Map<String, Object> m = new LinkedHashMap<>();
      copyIfPresent(src, m, "contextPath");
      copyIfPresent(src, m, "httpMethodRestrict");
      copyIfPresent(src, m, "path");
      node.setExpectedProperties(m);
      return;
    }
    if ("script".equals(t)) {
      Map<String, Object> m = new LinkedHashMap<>();
      copyIfPresent(src, m, "script");
      node.setExpectedProperties(m);
      return;
    }
    if ("if".equals(t)) {
      Map<String, Object> m = new LinkedHashMap<>();
      copyIfPresent(src, m, "condition");
      copyIfPresent(src, m, "priority");
      node.setExpectedProperties(m);
      return;
    }
    if ("else".equals(t) || "condition".equals(t)) {
      node.setExpectedProperties(new LinkedHashMap<>());
      return;
    }
    Map<String, Object> copy = new LinkedHashMap<>(src);
    copy.keySet().removeAll(List.of("propertiesToExportInSeparateFile", "exportFileExtension"));
    node.setExpectedProperties(copy);
  }

  private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
    if (from.containsKey(key)) {
      to.put(key, from.get(key));
    }
  }
}
