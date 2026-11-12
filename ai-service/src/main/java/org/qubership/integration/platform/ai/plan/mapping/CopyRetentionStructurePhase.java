package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;

/** Preserves COPY origins before intervening processing without changing the message body. */
public final class CopyRetentionStructurePhase {
  private CopyRetentionStructurePhase() {}

  public static ChainPlanGraph remove(ChainPlanGraph graph) {
    List<ChainPlanNode> nodes = new ArrayList<>(graph.nodes());
    List<ChainPlanEdge> edges = new ArrayList<>(graph.edges());
    for (ChainPlanNode site : graph.nodes()) {
      if (site.properties() == null || site.properties().stream().noneMatch(property ->
          "nativeRetentionSite".equals(property.key()) && "true".equals(property.value()))) continue;
      List<ChainPlanEdge> incoming = edges.stream().filter(edge -> site.nodeId().equals(edge.toNodeId())).toList();
      List<ChainPlanEdge> outgoing = edges.stream().filter(edge -> site.nodeId().equals(edge.fromNodeId())).toList();
      if (!"script".equals(site.type()) || incoming.size() != 1 || outgoing.size() != 1) {
        throw new IllegalArgumentException("Compiler retention site is not a linear script boundary");
      }
      ChainPlanEdge before = incoming.getFirst();
      ChainPlanEdge after = outgoing.getFirst();
      String derived = site.nodeId() + "-boundary";
      if (!derived.equals(before.edgeId()) && !derived.equals(after.edgeId())) {
        throw new IllegalArgumentException("Compiler retention edge ownership is missing");
      }
      String originalId = derived.equals(before.edgeId()) ? after.edgeId() : before.edgeId();
      edges.remove(before);
      edges.remove(after);
      edges.add(new ChainPlanEdge(originalId, before.fromNodeId(), after.toNodeId(), before.scopeNodeId()));
      nodes.remove(site);
    }
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.copyOf(edges));
  }

  public static ChainPlanGraph placeApproved(ChainPlanGraph graph,
      org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief brief,
      BiFunction<String, MappingPort, String> resolveRef) {
    if (brief == null) return graph;
    ChainPlanGraph preview = org.qubership.integration.platform.ai.qipknowledge.validation.RequestedElementValues
        .seedBodies(graph, brief.facts());
    return place(graph, CopyMappingMaterializationValidator.ordinaryMappings(brief, preview), resolveRef);
  }

  public static ChainPlanGraph place(ChainPlanGraph graph, List<MappingIntent> intents,
      BiFunction<String, MappingPort, String> resolveRef) {
    boolean hasProcessing = graph.nodes().stream().anyMatch(node -> "script".equals(node.type())
        && !CopyMappingPlanner.nativeRetentionSite(node) && MappingExecutionSite.mappingIntentId(node) == null);
    Map<String, MappingSource.Message> needed = new LinkedHashMap<>();
    for (MappingIntent intent : intents) {
      for (var rule : intent.rules()) {
        if (!(rule.value() instanceof MappingValue.Copy copy)
            || !(copy.source() instanceof MappingSource.Message origin)
            || origin.interactionId() == null || origin.interactionId().isBlank() || origin.port() == null) continue;
        if (hasProcessing || !resolveRef.apply(origin.interactionId(), origin.port())
                .equals(resolveRef.apply(intent.sourceRef(), intent.sourcePort()))
            || origin.port() != intent.sourcePort()) {
          needed.putIfAbsent(CopyMappingPlanner.retentionSiteId(origin), origin);
        }
      }
    }
    if (needed.isEmpty()) return graph;
    List<ChainPlanNode> nodes = new ArrayList<>(graph.nodes());
    List<ChainPlanEdge> edges = new ArrayList<>(graph.edges());
    for (var entry : needed.entrySet()) {
      String siteId = entry.getKey();
      MappingSource.Message origin = entry.getValue();
      String reference = resolveRef.apply(origin.interactionId(), origin.port());
      List<ChainPlanNode> producers = nodes.stream().filter(node -> reference.equals(node.nodeId())
          || reference.equals(node.semanticNodeId().orElse(null))
          || origin.interactionId().equals(node.serviceCallId().orElse(null))).toList();
      if (producers.size() != 1) throw new IllegalArgumentException("Retention origin must resolve to one graph node");
      ChainPlanNode producer = producers.getFirst();
      if (producer.parentNodeId() != null && !producer.parentNodeId().isBlank()) {
        throw new IllegalArgumentException("Native COPY retention supports root linear flows only");
      }
      boolean before = origin.port() == MappingPort.REQUEST;
      List<ChainPlanEdge> atBoundary = edges.stream().filter(edge -> before
          ? producer.nodeId().equals(edge.toNodeId()) : producer.nodeId().equals(edge.fromNodeId())).toList();
      if (atBoundary.size() != 1) throw new IllegalArgumentException("Retention needs one linear producer boundary");
      ChainPlanEdge boundary = atBoundary.getFirst();
      List<ChainPlanNode> occupied = nodes.stream().filter(node -> siteId.equals(node.nodeId())).toList();
      if (!occupied.isEmpty()) {
        ChainPlanNode existing = occupied.getFirst();
        boolean owned = "script".equals(existing.type()) && existing.properties() != null
            && existing.properties().stream().anyMatch(property -> "nativeRetentionSite".equals(property.key())
                && "true".equals(property.value()));
        boolean adjacent = before ? siteId.equals(boundary.fromNodeId()) : siteId.equals(boundary.toNodeId());
        if (!owned || !adjacent) throw new IllegalArgumentException("Retention site identity is occupied or moved");
        continue;
      }
      String edgeId = siteId + "-boundary";
      if (edges.stream().anyMatch(edge -> edgeId.equals(edge.edgeId()))) {
        throw new IllegalArgumentException("Retention edge identity is occupied");
      }
      nodes.add(new ChainPlanNode(siteId, "script", "Preserve mapping source", null, null,
          List.of(new PlanProperty("nativeRetentionSite", "true"))));
      edges.remove(boundary);
      if (before) {
        edges.add(new ChainPlanEdge(boundary.edgeId(), boundary.fromNodeId(), siteId, boundary.scopeNodeId()));
        edges.add(new ChainPlanEdge(edgeId, siteId, producer.nodeId(), boundary.scopeNodeId()));
      } else {
        edges.add(new ChainPlanEdge(boundary.edgeId(), siteId, boundary.toNodeId(), boundary.scopeNodeId()));
        edges.add(new ChainPlanEdge(edgeId, producer.nodeId(), siteId, boundary.scopeNodeId()));
      }
    }
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.copyOf(edges));
  }
}
