package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Structure-generator seam: insert one mapper-2 or script shell and its edges per approved mapping
 * intent.
 */
public final class MappingStructurePhase {

  private MappingStructurePhase() {}

  public static ChainPlanGraph placeShells(ChainPlanGraph graph, RequirementBrief brief) {
    if (graph == null) {
      throw new IllegalArgumentException("graph is required");
    }
    RequirementBrief adapted = brief;
    if (adapted == null || adapted.mappingIntents().isEmpty()) {
      return graph;
    }
    ChainPlanGraph current = graph;
    for (MappingIntent intent : adapted.mappingIntents()) {
      if (intent == null || intent.mappingIntentId().isBlank()) {
        continue;
      }
      current = placeOne(current, intent);
    }
    return current;
  }

  private static ChainPlanGraph placeOne(ChainPlanGraph graph, MappingIntent intent) {
    if (findSite(graph, intent.mappingIntentId()) != null) {
      return graph;
    }
    MappingMechanism mechanism =
        MappingMechanismSelector.select(intent)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        MappingMechanismSelector.clarification(intent)
                            .orElse(
                                "Approved mapping intent '"
                                    + intent.mappingIntentId()
                                    + "' has no compatible execution mechanism.")));
    String elementType = shellType(mechanism);
    Map<String, ChainPlanNode> nodesById = indexNodes(graph);
    ChainPlanNode source = nodesById.get(intent.sourceRef());
    ChainPlanNode target = nodesById.get(intent.targetRef());
    if (source == null) {
      throw new IllegalStateException(
          "Approved mapping intent '"
              + intent.mappingIntentId()
              + "' cannot be placed: source node '"
              + intent.sourceRef()
              + "' is missing. Structure generation must insert the source before the transform"
              + " shell.");
    }
    if (target == null) {
      throw new IllegalStateException(
          "Approved mapping intent '"
              + intent.mappingIntentId()
              + "' cannot be placed: target node '"
              + intent.targetRef()
              + "' is missing. Structure generation must insert the target before the transform"
              + " shell.");
    }
    ChainPlanNode untagged =
        untaggedShellOnBoundary(graph, intent.sourceRef(), intent.targetRef(), elementType);
    if (untagged != null) {
      return replaceNode(
          graph, MappingExecutionSite.withMappingIntentId(untagged, intent.mappingIntentId()));
    }
    List<ChainPlanEdge> boundaryEdges =
        directEdges(graph, intent.sourceRef(), intent.targetRef());
    if (boundaryEdges.isEmpty()) {
      if (intent.targetPort() == MappingPort.OUTPUT
          && outgoingCount(graph, intent.sourceRef()) == 0) {
        return appendTerminalShell(graph, intent, source, mechanism);
      }
      throw new IllegalStateException(
          "Approved mapping intent '"
              + intent.mappingIntentId()
              + "' cannot be placed: there is no direct edge from '"
              + intent.sourceRef()
              + "' to '"
              + intent.targetRef()
              + "'. Structure generation must connect the semantic boundary before inserting the"
              + " transform shell.");
    }
    String mapperId = "transform-" + intent.mappingIntentId();
    ChainPlanNode mapper =
        MappingExecutionSite.withMappingIntentId(
            new ChainPlanNode(
                mapperId,
                elementType,
                shellLabel(mechanism, intent.mappingIntentId()),
                source.parentNodeId(),
                null,
                List.of()),
            intent.mappingIntentId());
    List<ChainPlanNode> nodes = new ArrayList<>(graph.nodes());
    int insertAt = indexOf(nodes, intent.targetRef());
    if (insertAt < 0) {
      nodes.add(mapper);
    } else {
      nodes.add(insertAt, mapper);
    }
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges()) {
      if (intent.sourceRef().equals(edge.fromNodeId())
          && intent.targetRef().equals(edge.toNodeId())) {
        continue;
      }
      edges.add(edge);
    }
    ChainPlanEdge first = boundaryEdges.getFirst();
    edges.add(
        new ChainPlanEdge(
            "e-" + intent.sourceRef() + "-" + mapperId,
            intent.sourceRef(),
            mapperId,
            first.scopeNodeId()));
    edges.add(
        new ChainPlanEdge(
            "e-" + mapperId + "-" + intent.targetRef(),
            mapperId,
            intent.targetRef(),
            first.scopeNodeId()));
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.copyOf(edges));
  }

  private static ChainPlanGraph appendTerminalShell(
      ChainPlanGraph graph,
      MappingIntent intent,
      ChainPlanNode source,
      MappingMechanism mechanism) {
    String mapperId = "transform-" + intent.mappingIntentId();
    ChainPlanNode mapper =
        MappingExecutionSite.withMappingIntentId(
            new ChainPlanNode(
                mapperId,
                shellType(mechanism),
                shellLabel(mechanism, intent.mappingIntentId()),
                source.parentNodeId(),
                null,
                List.of()),
            intent.mappingIntentId());
    List<ChainPlanNode> nodes = new ArrayList<>(graph.nodes());
    nodes.add(mapper);
    List<ChainPlanEdge> edges = new ArrayList<>();
    if (graph.edges() != null) {
      edges.addAll(graph.edges());
    }
    edges.add(
        new ChainPlanEdge(
            "e-" + intent.sourceRef() + "-" + mapperId, intent.sourceRef(), mapperId, null));
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.copyOf(edges));
  }

  private static int outgoingCount(ChainPlanGraph graph, String nodeId) {
    int count = 0;
    if (graph.edges() == null) {
      return 0;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (nodeId.equals(edge.fromNodeId())) {
        count++;
      }
    }
    return count;
  }

  private static String shellType(MappingMechanism mechanism) {
    return mechanism == MappingMechanism.SCRIPT
        ? MappingExecutionSite.SCRIPT_ELEMENT_TYPE
        : MappingExecutionSite.ELEMENT_TYPE;
  }

  private static String shellLabel(MappingMechanism mechanism, String mappingIntentId) {
    return mechanism == MappingMechanism.SCRIPT
        ? "Script " + mappingIntentId
        : "Map " + mappingIntentId;
  }

  private static ChainPlanNode findSite(ChainPlanGraph graph, String mappingIntentId) {
    for (ChainPlanNode node : graph.nodes()) {
      if (mappingIntentId.equals(MappingExecutionSite.mappingIntentId(node))) {
        return node;
      }
    }
    return null;
  }

  private static ChainPlanNode untaggedShellOnBoundary(
      ChainPlanGraph graph, String sourceRef, String targetRef, String elementType) {
    for (ChainPlanNode node : graph.nodes()) {
      if (!elementType.equals(node.type()) || !MappingExecutionSite.isTransformShell(node)) {
        continue;
      }
      if (MappingExecutionSite.mappingIntentId(node) != null
          && !MappingExecutionSite.mappingIntentId(node).isBlank()) {
        continue;
      }
      boolean fromSource = !directEdges(graph, sourceRef, node.nodeId()).isEmpty();
      boolean toTarget = !directEdges(graph, node.nodeId(), targetRef).isEmpty();
      if (fromSource && toTarget) {
        return node;
      }
    }
    return null;
  }

  private static List<ChainPlanEdge> directEdges(
      ChainPlanGraph graph, String fromNodeId, String toNodeId) {
    List<ChainPlanEdge> matches = new ArrayList<>();
    if (graph.edges() == null) {
      return matches;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (fromNodeId.equals(edge.fromNodeId()) && toNodeId.equals(edge.toNodeId())) {
        matches.add(edge);
      }
    }
    return matches;
  }

  private static Map<String, ChainPlanNode> indexNodes(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> nodesById = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      nodesById.put(node.nodeId(), node);
    }
    return nodesById;
  }

  private static int indexOf(List<ChainPlanNode> nodes, String nodeId) {
    for (int i = 0; i < nodes.size(); i++) {
      if (nodeId.equals(nodes.get(i).nodeId())) {
        return i;
      }
    }
    return -1;
  }

  private static ChainPlanGraph replaceNode(ChainPlanGraph graph, ChainPlanNode updated) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      nodes.add(updated.nodeId().equals(node.nodeId()) ? updated : node);
    }
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), List.copyOf(nodes), graph.edges());
  }
}
