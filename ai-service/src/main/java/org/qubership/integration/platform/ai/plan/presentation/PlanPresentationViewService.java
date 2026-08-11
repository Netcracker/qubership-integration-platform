package org.qubership.integration.platform.ai.plan.presentation;

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
import java.util.Set;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.presentation.MermaidFlowchart;

/** Deterministic plan views for read-only Q&A. */
@ApplicationScoped
public class PlanPresentationViewService {

  private static final Set<String> SCRIPT_PROPERTY_KEYS =
      Set.of("script", "body", "expression", "groovy", "language");

  private final ObjectMapper objectMapper;

  @Inject
  public PlanPresentationViewService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String formatMermaidFlowchart(ChainPlanGraph graph) {
    Objects.requireNonNull(graph, "graph");
    Map<String, ChainPlanNode> nodesById = indexNodes(graph);
    List<MermaidFlowchart.Edge> edges =
        graph.edges() == null
            ? List.of()
            : graph.edges().stream()
                .filter(edge -> edge.fromNodeId() != null && edge.toNodeId() != null)
                .map(
                    edge ->
                        new MermaidFlowchart.Edge(
                            edge.fromNodeId(),
                            edge.toNodeId(),
                            labelFor(nodesById, edge.fromNodeId()),
                            labelFor(nodesById, edge.toNodeId())))
                .toList();
    List<MermaidFlowchart.Node> nodes =
        nodesById.values().stream()
            .map(node -> new MermaidFlowchart.Node(node.nodeId(), nodeLabel(node)))
            .toList();
    return MermaidFlowchart.format(edges, nodes);
  }

  public String formatTree(ChainPlanGraph graph) {
    Objects.requireNonNull(graph, "graph");
    Map<String, ChainPlanNode> nodesById = indexNodes(graph);
    Map<String, List<ChainPlanNode>> childrenByParent = new LinkedHashMap<>();
    List<ChainPlanNode> roots = new ArrayList<>();
    for (ChainPlanNode node : nodesById.values()) {
      String parentId = node.parentNodeId();
      if (parentId == null || parentId.isBlank()) {
        roots.add(node);
      } else {
        childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(node);
      }
    }
    roots.sort(Comparator.comparing(ChainPlanNode::nodeId, Comparator.nullsLast(String::compareTo)));
    StringBuilder sb = new StringBuilder("Plan tree:\n");
    for (ChainPlanNode root : roots) {
      appendTreeNode(sb, root, childrenByParent, 0);
    }
    return sb.toString().stripTrailing();
  }

  public String formatPrettyJson(ChainPlanGraph graph) throws JsonProcessingException {
    return "```json\n"
        + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(graph)
        + "\n```";
  }

  public String formatScriptDetails(ChainPlanGraph graph) {
    Objects.requireNonNull(graph, "graph");
    List<ChainPlanNode> scriptNodes =
        graph.nodes() == null
            ? List.of()
            : graph.nodes().stream()
                .filter(node -> "script".equals(node.type()))
                .toList();
    if (scriptNodes.isEmpty()) {
      return "The plan has no script nodes.";
    }

    StringBuilder sb = new StringBuilder("Scripts in the plan:\n");
    boolean anyBody = false;
    for (ChainPlanNode node : scriptNodes) {
      sb.append("- ")
          .append(node.label() != null ? node.label() : node.nodeId())
          .append(" (")
          .append(node.nodeId())
          .append(")\n");
      List<PlanProperty> scriptProps = scriptProperties(node);
      if (scriptProps.isEmpty()) {
        sb.append("  No script body in plan properties.\n");
      } else {
        anyBody = true;
        for (PlanProperty property : scriptProps) {
          sb.append("  ").append(property.key()).append(":\n");
          sb.append("  ```\n").append(property.value()).append("\n  ```\n");
        }
      }
    }
    if (!anyBody) {
      sb.append(
          "\nScript nodes exist, but script/body/expression properties are missing from the plan.");
    }
    return sb.toString().stripTrailing();
  }

  private static List<PlanProperty> scriptProperties(ChainPlanNode node) {
    if (node.properties() == null) {
      return List.of();
    }
    return node.properties().stream()
        .filter(
            property ->
                property.key() != null
                    && SCRIPT_PROPERTY_KEYS.contains(property.key().toLowerCase()))
        .toList();
  }

  private static void appendTreeNode(
      StringBuilder sb,
      ChainPlanNode node,
      Map<String, List<ChainPlanNode>> childrenByParent,
      int depth) {
    sb.append("  ".repeat(depth))
        .append("- ")
        .append(nodeLabel(node))
        .append(" [")
        .append(node.type())
        .append("]\n");
    List<ChainPlanNode> children = childrenByParent.getOrDefault(node.nodeId(), List.of());
    children.sort(Comparator.comparing(ChainPlanNode::nodeId, Comparator.nullsLast(String::compareTo)));
    for (ChainPlanNode child : children) {
      appendTreeNode(sb, child, childrenByParent, depth + 1);
    }
  }

  private static Map<String, ChainPlanNode> indexNodes(ChainPlanGraph graph) {
    if (graph.nodes() == null) {
      return Map.of();
    }
    return graph.nodes().stream()
        .filter(node -> node.nodeId() != null)
        .collect(
            Collectors.toMap(ChainPlanNode::nodeId, node -> node, (a, b) -> a, LinkedHashMap::new));
  }

  private static String labelFor(Map<String, ChainPlanNode> nodesById, String nodeId) {
    ChainPlanNode node = nodesById.get(nodeId);
    return node != null ? nodeLabel(node) : nodeId;
  }

  private static String nodeLabel(ChainPlanNode node) {
    if (node.label() != null && !node.label().isBlank()) {
      return node.label();
    }
    if (node.type() != null && !node.type().isBlank()) {
      return node.type();
    }
    return node.nodeId();
  }
}
