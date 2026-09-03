package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Structure-generator seam: reconcile one transform shell per approved mapping intent. The approved
 * collection is desired state: insert missing sites, reuse matching sites, and remove tagged
 * sites whose identifiers are absent.
 */
public final class MappingStructurePhase {

  private MappingStructurePhase() {}

  public static ChainPlanGraph placeShells(ChainPlanGraph graph, RequirementBrief brief) {
    if (graph == null) {
      throw new IllegalArgumentException("graph is required");
    }
    List<MappingIntent> intents = brief == null ? List.of() : brief.mappingIntents();
    return reconcile(graph, intents);
  }

  public static ChainPlanGraph reconcile(ChainPlanGraph graph, List<MappingIntent> mappingIntents) {
    if (graph == null) {
      throw new IllegalArgumentException("graph is required");
    }
    List<MappingIntent> desired = mappingIntents == null ? List.of() : mappingIntents;
    Map<String, MappingIntent> byId = indexIntents(desired);
    Map<String, List<ChainPlanNode>> sitesByIntent = indexTaggedSites(graph);
    rejectDuplicateSites(sitesByIntent);
    ChainPlanGraph current = graph;
    for (Map.Entry<String, List<ChainPlanNode>> entry : sitesByIntent.entrySet()) {
      if (byId.containsKey(entry.getKey())) {
        continue;
      }
      for (ChainPlanNode site : entry.getValue()) {
        current = removeObsoleteSite(current, site);
      }
    }
    sitesByIntent = indexTaggedSites(current);
    for (MappingIntent intent : desired) {
      if (intent == null || intent.mappingIntentId().isBlank()) {
        continue;
      }
      List<ChainPlanNode> sites = sitesByIntent.getOrDefault(intent.mappingIntentId(), List.of());
      if (sites.isEmpty()) {
        current = placeOne(current, intent);
        continue;
      }
      ChainPlanNode site = sites.getFirst();
      if (!matches(current, site, intent)) {
        throw new IllegalStateException(
            "Mapping execution site '"
                + site.nodeId()
                + "' for mapping intent '"
                + intent.mappingIntentId()
                + "' does not match the approved source, target, ports, or execution mechanism."
                + " Remove the site or restore a deterministic boundary.");
      }
      if (!MappingExecutionSiteValidator.isReachable(current, site.nodeId())) {
        throw new IllegalStateException(
            "Transform node '"
                + site.nodeId()
                + "' is not reachable from any trigger. Connect it on the mapping boundary so"
                + " compilation can execute the intent.");
      }
      current = reuseMatchingSite(current, site);
    }
    return current;
  }

  private static Map<String, MappingIntent> indexIntents(List<MappingIntent> intents) {
    Map<String, MappingIntent> byId = new LinkedHashMap<>();
    for (MappingIntent intent : intents) {
      if (intent == null || intent.mappingIntentId().isBlank()) {
        continue;
      }
      if (byId.containsKey(intent.mappingIntentId())) {
        throw new IllegalStateException(
            "Duplicate mapping intent '"
                + intent.mappingIntentId()
                + "' in the approved collection. Keep exactly one intent per identifier.");
      }
      byId.put(intent.mappingIntentId(), intent);
    }
    return byId;
  }

  private static void rejectDuplicateSites(Map<String, List<ChainPlanNode>> sitesByIntent) {
    for (Map.Entry<String, List<ChainPlanNode>> entry : sitesByIntent.entrySet()) {
      if (entry.getValue().size() > 1) {
        throw new IllegalStateException(
            "Mapping intent '"
                + entry.getKey()
                + "' is claimed by more than one execution site. Keep exactly one mapper-2 or"
                + " script node for that intent.");
      }
    }
  }

  private static ChainPlanGraph reuseMatchingSite(ChainPlanGraph graph, ChainPlanNode site) {
    ChainPlanNode cleared = MappingExecutionSite.withoutGeneratedArtifact(site);
    if (cleared == site) {
      return graph;
    }
    return replaceNode(graph, cleared);
  }

  private static boolean matches(ChainPlanGraph graph, ChainPlanNode site, MappingIntent intent) {
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
    if (!shellType(mechanism).equals(site.type())) {
      return false;
    }
    if (!portsMatch(site, intent)) {
      return false;
    }
    List<ChainPlanEdge> incoming = incomingEdges(graph, site.nodeId());
    List<ChainPlanEdge> outgoing = outgoingEdges(graph, site.nodeId());
    if (incoming.size() != 1) {
      return false;
    }
    if (!intent.sourceRef().equals(incoming.getFirst().fromNodeId())) {
      return false;
    }
    if (outgoing.isEmpty()) {
      return intent.targetPort() == MappingPort.OUTPUT;
    }
    return outgoing.size() == 1 && intent.targetRef().equals(outgoing.getFirst().toNodeId());
  }

  private static boolean portsMatch(ChainPlanNode site, MappingIntent intent) {
    String storedSource = MappingExecutionSite.mappingSourcePort(site);
    String storedTarget = MappingExecutionSite.mappingTargetPort(site);
    if ((storedSource == null || storedSource.isBlank())
        && (storedTarget == null || storedTarget.isBlank())) {
      return true;
    }
    return Objects.equals(portName(intent.sourcePort()), storedSource)
        && Objects.equals(portName(intent.targetPort()), storedTarget);
  }

  private static ChainPlanGraph placeOne(ChainPlanGraph graph, MappingIntent intent) {
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
    List<ChainPlanNode> untagged =
        untaggedShellsOnBoundary(graph, intent.sourceRef(), intent.targetRef(), elementType);
    if (untagged.size() > 1) {
      throw new IllegalStateException(
          "Mapping intent '"
              + intent.mappingIntentId()
              + "' matches more than one untagged transform shell on the boundary from '"
              + intent.sourceRef()
              + "' to '"
              + intent.targetRef()
              + "'. Keep exactly one shell or tag it with the intent identifier.");
    }
    if (untagged.size() == 1) {
      return replaceNode(graph, taggedShell(untagged.getFirst(), intent));
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
        taggedShell(
            new ChainPlanNode(
                mapperId,
                elementType,
                shellLabel(mechanism, intent.mappingIntentId()),
                source.parentNodeId(),
                null,
                List.of()),
            intent);
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
        taggedShell(
            new ChainPlanNode(
                mapperId,
                shellType(mechanism),
                shellLabel(mechanism, intent.mappingIntentId()),
                source.parentNodeId(),
                null,
                List.of()),
            intent);
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

  private static ChainPlanGraph removeObsoleteSite(ChainPlanGraph graph, ChainPlanNode site) {
    List<ChainPlanEdge> incoming = incomingEdges(graph, site.nodeId());
    List<ChainPlanEdge> outgoing = outgoingEdges(graph, site.nodeId());
    if (outgoing.isEmpty()) {
      return dropNodeAndEdges(graph, site.nodeId());
    }
    if (incoming.size() != 1 || outgoing.size() != 1) {
      throw new IllegalStateException(
          "Cannot remove transform node '"
              + site.nodeId()
              + "' for an obsolete mapping intent: the site does not have exactly one incoming"
              + " source and one outgoing target. Do not guess a new transition.");
    }
    ChainPlanEdge in = incoming.getFirst();
    ChainPlanEdge out = outgoing.getFirst();
    String scope = preservedScope(in, out);
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (!site.nodeId().equals(node.nodeId())) {
        nodes.add(node);
      }
    }
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges()) {
      if (site.nodeId().equals(edge.fromNodeId()) || site.nodeId().equals(edge.toNodeId())) {
        continue;
      }
      edges.add(edge);
    }
    edges.add(
        new ChainPlanEdge(
            reconnectEdgeId(in, out), in.fromNodeId(), out.toNodeId(), scope));
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.copyOf(edges));
  }

  private static ChainPlanGraph dropNodeAndEdges(ChainPlanGraph graph, String nodeId) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (!nodeId.equals(node.nodeId())) {
        nodes.add(node);
      }
    }
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges()) {
      if (nodeId.equals(edge.fromNodeId()) || nodeId.equals(edge.toNodeId())) {
        continue;
      }
      edges.add(edge);
    }
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), List.copyOf(nodes), List.copyOf(edges));
  }

  private static String preservedScope(ChainPlanEdge incoming, ChainPlanEdge outgoing) {
    String inScope = incoming.scopeNodeId();
    String outScope = outgoing.scopeNodeId();
    if (inScope == null || inScope.isBlank()) {
      return blankToNull(outScope);
    }
    if (outScope == null || outScope.isBlank() || inScope.equals(outScope)) {
      return inScope;
    }
    throw new IllegalStateException(
        "Cannot remove transform node on edge '"
            + incoming.edgeId()
            + "': incoming and outgoing semantic edge scopes differ. Do not guess a new"
            + " transition.");
  }

  private static String reconnectEdgeId(ChainPlanEdge incoming, ChainPlanEdge outgoing) {
    return "e-" + incoming.fromNodeId() + "-" + outgoing.toNodeId();
  }

  private static int outgoingCount(ChainPlanGraph graph, String nodeId) {
    return outgoingEdges(graph, nodeId).size();
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

  private static ChainPlanNode taggedShell(ChainPlanNode node, MappingIntent intent) {
    return MappingExecutionSite.withBoundary(
        node, intent.mappingIntentId(), portName(intent.sourcePort()), portName(intent.targetPort()));
  }

  private static String portName(MappingPort port) {
    return port == null ? null : port.name();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Map<String, List<ChainPlanNode>> indexTaggedSites(ChainPlanGraph graph) {
    Map<String, List<ChainPlanNode>> sites = new LinkedHashMap<>();
    if (graph.nodes() == null) {
      return sites;
    }
    for (ChainPlanNode node : graph.nodes()) {
      String mappingIntentId = MappingExecutionSite.mappingIntentId(node);
      if (mappingIntentId == null || mappingIntentId.isBlank()) {
        continue;
      }
      if (!MappingExecutionSite.isTransformShell(node)) {
        throw new IllegalStateException(
            "Node '"
                + node.nodeId()
                + "' claims mapping intent '"
                + mappingIntentId
                + "' but is not a mapper-2 or script execution site.");
      }
      sites.computeIfAbsent(mappingIntentId, ignored -> new ArrayList<>()).add(node);
    }
    return sites;
  }

  private static List<ChainPlanNode> untaggedShellsOnBoundary(
      ChainPlanGraph graph, String sourceRef, String targetRef, String elementType) {
    List<ChainPlanNode> matches = new ArrayList<>();
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
        matches.add(node);
      }
    }
    return matches;
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

  private static List<ChainPlanEdge> incomingEdges(ChainPlanGraph graph, String nodeId) {
    List<ChainPlanEdge> matches = new ArrayList<>();
    if (graph.edges() == null) {
      return matches;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (nodeId.equals(edge.toNodeId())) {
        matches.add(edge);
      }
    }
    return matches;
  }

  private static List<ChainPlanEdge> outgoingEdges(ChainPlanGraph graph, String nodeId) {
    List<ChainPlanEdge> matches = new ArrayList<>();
    if (graph.edges() == null) {
      return matches;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (nodeId.equals(edge.fromNodeId())) {
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
