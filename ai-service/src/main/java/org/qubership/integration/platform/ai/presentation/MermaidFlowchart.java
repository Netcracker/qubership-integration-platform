package org.qubership.integration.platform.ai.presentation;

import java.util.List;

/** Shared Mermaid flowchart formatting for plan and catalog chain views. */
public final class MermaidFlowchart {

  public record Edge(String fromId, String toId, String fromLabel, String toLabel) {}

  public record Node(String id, String label) {}

  private MermaidFlowchart() {}

  public static String format(List<Edge> edges, List<Node> nodesWithoutEdges) {
    StringBuilder sb = new StringBuilder("```mermaid\nflowchart TD\n");
    if (edges != null && !edges.isEmpty()) {
      for (Edge edge : edges) {
        if (edge.fromId() == null || edge.toId() == null) {
          continue;
        }
        appendNodeEdge(sb, edge.fromId(), edge.fromLabel(), edge.toId(), edge.toLabel());
      }
    } else if (nodesWithoutEdges != null) {
      for (Node node : nodesWithoutEdges) {
        if (node.id() == null) {
          continue;
        }
        sb.append("  ")
            .append(mermaidNodeId(node.id()))
            .append("[\"")
            .append(escapeMermaidLabel(node.label() != null ? node.label() : node.id()))
            .append("\"]\n");
      }
    }
    sb.append("```");
    return sb.toString();
  }

  private static void appendNodeEdge(
      StringBuilder sb, String fromId, String fromLabel, String toId, String toLabel) {
    sb.append("  ")
        .append(mermaidNodeId(fromId))
        .append("[\"")
        .append(escapeMermaidLabel(fromLabel != null ? fromLabel : fromId))
        .append("\"] --> ")
        .append(mermaidNodeId(toId))
        .append("[\"")
        .append(escapeMermaidLabel(toLabel != null ? toLabel : toId))
        .append("\"]\n");
  }

  public static String mermaidNodeId(String nodeId) {
    return nodeId.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  public static String escapeMermaidLabel(String label) {
    return label.replace("\"", "'");
  }
}
