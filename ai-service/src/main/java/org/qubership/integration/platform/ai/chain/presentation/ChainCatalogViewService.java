package org.qubership.integration.platform.ai.chain.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.presentation.MermaidFlowchart;
/** Deterministic catalog chain views for read-only Q&A. */
@ApplicationScoped
public class ChainCatalogViewService {

  private final ObjectMapper objectMapper;

  @Inject
  public ChainCatalogViewService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String formatMermaidFlowchart(ChainCatalogFacts facts) {
    Objects.requireNonNull(facts, "facts");
    Map<String, ChainCatalogElement> byId = indexElements(facts);
    List<MermaidFlowchart.Edge> edges =
        facts.dependencies() == null
            ? List.of()
            : facts.dependencies().stream()
                .filter(dep -> dep.fromElementId() != null && dep.toElementId() != null)
                .map(
                    dep ->
                        new MermaidFlowchart.Edge(
                            dep.fromElementId(),
                            dep.toElementId(),
                            labelFor(byId, dep.fromElementId()),
                            labelFor(byId, dep.toElementId())))
                .toList();
    List<MermaidFlowchart.Node> nodes =
        byId.values().stream()
            .map(element -> new MermaidFlowchart.Node(element.elementId(), elementLabel(element)))
            .toList();
    return MermaidFlowchart.format(edges, nodes);
  }

  public String formatTree(ChainCatalogFacts facts) {
    Objects.requireNonNull(facts, "facts");
    Map<String, ChainCatalogElement> byId = indexElements(facts);
    Map<String, List<ChainCatalogElement>> childrenByParent = new LinkedHashMap<>();
    List<ChainCatalogElement> roots = new ArrayList<>();
    for (ChainCatalogElement element : byId.values()) {
      String parentId = element.parentElementId();
      if (parentId == null || parentId.isBlank()) {
        roots.add(element);
      } else {
        childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(element);
      }
    }
    roots.sort(
        Comparator.comparing(
            ChainCatalogElement::elementId, Comparator.nullsLast(String::compareTo)));
    StringBuilder sb = new StringBuilder("Chain tree:\n");
    for (ChainCatalogElement root : roots) {
      appendTreeNode(sb, root, childrenByParent, 0);
    }
    return sb.toString().stripTrailing();
  }

  public String formatPrettyJson(ChainCatalogFacts facts) throws JsonProcessingException {
    return "```json\n"
        + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts)
        + "\n```";
  }

  public String formatScriptDetails(ChainCatalogFacts facts) {
    Objects.requireNonNull(facts, "facts");
    List<ChainCatalogElement> scriptElements =
        facts.elements().stream().filter(element -> "script".equals(element.type())).toList();
    if (scriptElements.isEmpty()) {
      return "The chain has no script elements.";
    }

    StringBuilder sb = new StringBuilder("Scripts in the chain:\n");
    boolean anyBody = false;
    for (ChainCatalogElement element : scriptElements) {
      sb.append("- ")
          .append(element.name())
          .append(" (")
          .append(element.elementId())
          .append(")\n");
      if (element.scriptProperties() == null || element.scriptProperties().isEmpty()) {
        sb.append("  No script body in element properties.\n");
      } else {
        anyBody = true;
        for (Map.Entry<String, String> entry : element.scriptProperties().entrySet()) {
          sb.append("  ").append(entry.getKey()).append(":\n");
          sb.append("  ```\n").append(entry.getValue()).append("\n  ```\n");
        }
      }
    }
    if (!anyBody) {
      sb.append(
          "\nScript elements exist, but script/body/expression properties are missing.");
    }
    return sb.toString().stripTrailing();
  }

  private static void appendTreeNode(
      StringBuilder sb,
      ChainCatalogElement element,
      Map<String, List<ChainCatalogElement>> childrenByParent,
      int depth) {
    sb.append("  ".repeat(depth))
        .append("- ")
        .append(elementLabel(element))
        .append(" [")
        .append(element.type())
        .append("]\n");
    List<ChainCatalogElement> children =
        new ArrayList<>(childrenByParent.getOrDefault(element.elementId(), List.of()));
    children.sort(
        Comparator.comparing(
            ChainCatalogElement::elementId, Comparator.nullsLast(String::compareTo)));
    for (ChainCatalogElement child : children) {
      appendTreeNode(sb, child, childrenByParent, depth + 1);
    }
  }

  private static Map<String, ChainCatalogElement> indexElements(ChainCatalogFacts facts) {
    if (facts.elements() == null) {
      return Map.of();
    }
    return facts.elements().stream()
        .filter(element -> element.elementId() != null)
        .collect(
            Collectors.toMap(
                ChainCatalogElement::elementId, element -> element, (a, b) -> a, LinkedHashMap::new));
  }

  private static String labelFor(Map<String, ChainCatalogElement> byId, String elementId) {
    ChainCatalogElement element = byId.get(elementId);
    return element != null ? elementLabel(element) : elementId;
  }

  private static String elementLabel(ChainCatalogElement element) {
    if (element.name() != null && !element.name().isBlank()) {
      return element.name();
    }
    if (element.type() != null && !element.type().isBlank()) {
      return element.type();
    }
    return element.elementId();
  }
}
