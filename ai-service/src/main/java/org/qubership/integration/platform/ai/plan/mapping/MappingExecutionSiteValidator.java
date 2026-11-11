package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

/**
 * Compiler check that each approved mapping intent has exactly one reachable, configured site.
 */
public final class MappingExecutionSiteValidator {

  private MappingExecutionSiteValidator() {}

  public static List<ValidationIssue> validate(
      ChainPlanGraph graph, List<MappingIntent> mappingIntents) {
    if (graph == null || mappingIntents == null) {
      return List.of();
    }
    List<ValidationIssue> issues = new ArrayList<>();
    Map<String, List<ChainPlanNode>> sitesByIntent = indexSites(graph);
    Set<String> approvedIds = new HashSet<>();
    for (MappingIntent intent : mappingIntents) {
      if (intent == null || intent.mappingIntentId().isBlank()) {
        continue;
      }
      approvedIds.add(intent.mappingIntentId());
      List<ChainPlanNode> sites = sitesByIntent.getOrDefault(intent.mappingIntentId(), List.of());
      if (sites.isEmpty()) {
        issues.add(
            blocker(
                "mapping-site-missing-" + intent.mappingIntentId(),
                "Approved mapping intent '"
                    + intent.mappingIntentId()
                    + "' has no mapper-2 or script execution site. Structure generation must"
                    + " insert one reachable transform shell before configuration.",
                "cip-structure-generator",
                List.of()));
        continue;
      }
      if (sites.size() > 1) {
        issues.add(
            blocker(
                "mapping-site-duplicate-" + intent.mappingIntentId(),
                "Mapping intent '"
                    + intent.mappingIntentId()
                    + "' is claimed by more than one execution site. Keep exactly one mapper-2 or"
                    + " script node for that intent.",
                "cip-structure-generator",
                sites.stream().map(ChainPlanNode::nodeId).toList()));
      }
      Set<String> reachable = reachableNodeIds(graph);
      for (ChainPlanNode site : sites) {
        if (!reachable.contains(site.nodeId())) {
          issues.add(
              blocker(
                  "mapping-site-unreachable-" + site.nodeId(),
                  "Transform node '"
                      + site.nodeId()
                      + "' is not reachable from any trigger. Connect it on the mapping boundary"
                      + " so compilation can execute the intent.",
                  "cip-structure-generator",
                  List.of(site.nodeId())));
        }
        if (incomingCount(graph, site.nodeId()) > 1) {
          issues.add(
              blocker(
                  "mapping-site-merge-" + site.nodeId(),
                  "Transform node '"
                      + site.nodeId()
                      + "' has more than one incoming edge. A merge is control flow only. Place"
                      + " the mapping on one source-to-target boundary, or add an explicit"
                      + " aggregation step.",
                  "cip-structure-generator",
                  List.of(site.nodeId())));
        }
        if (!MappingExecutionSite.isConfigured(site)) {
          issues.add(unconfiguredIssue(site));
        }
      }
    }
    for (Map.Entry<String, List<ChainPlanNode>> entry : sitesByIntent.entrySet()) {
      if (approvedIds.contains(entry.getKey())) {
        continue;
      }
      for (ChainPlanNode site : entry.getValue()) {
        issues.add(
            blocker(
                "mapping-site-unknown-" + site.nodeId(),
                "Node '"
                    + site.nodeId()
                    + "' claims mapping intent '"
                    + entry.getKey()
                    + "' that is not in the approved brief. Remove the site or restore the"
                    + " intent.",
                "cip-structure-generator",
                List.of(site.nodeId())));
      }
    }
    return List.copyOf(issues);
  }

  private static ValidationIssue unconfiguredIssue(ChainPlanNode site) {
    if (MappingExecutionSite.isScript(site)) {
      return blocker(
          "mapping-site-unconfigured-" + site.nodeId(),
          "Script node '"
              + site.nodeId()
              + "' is missing a script body. cip-script-generator must configure the existing"
              + " shell.",
          "cip-script-generator",
          List.of(site.nodeId()));
    }
    return blocker(
        "mapping-site-unconfigured-" + site.nodeId(),
        "Mapper-2 node '"
            + site.nodeId()
            + "' is missing mappingDescription. cip-transformation-generator must configure the"
            + " existing shell.",
        "cip-transformation-generator",
        List.of(site.nodeId()));
  }

  private static Map<String, List<ChainPlanNode>> indexSites(ChainPlanGraph graph) {
    Map<String, List<ChainPlanNode>> sites = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      String mappingIntentId = MappingExecutionSite.mappingIntentId(node);
      if (mappingIntentId == null || mappingIntentId.isBlank()) {
        continue;
      }
      sites.computeIfAbsent(mappingIntentId, ignored -> new ArrayList<>()).add(node);
    }
    return sites;
  }

  private static int incomingCount(ChainPlanGraph graph, String nodeId) {
    int count = 0;
    if (graph.edges() == null) {
      return 0;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (nodeId.equals(edge.toNodeId())) {
        count++;
      }
    }
    return count;
  }

  public static boolean isReachable(ChainPlanGraph graph, String nodeId) {
    return nodeId != null && reachableNodeIds(graph).contains(nodeId);
  }

  private static Set<String> reachableNodeIds(ChainPlanGraph graph) {
    Set<String> reachable = new HashSet<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (ChainPlanGraphValidator.isTriggerElementType(node.type())) {
        reachable.add(node.nodeId());
      }
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (ChainPlanNode node : graph.nodes()) {
        if (node.parentNodeId() != null
            && reachable.contains(node.parentNodeId())
            && reachable.add(node.nodeId())) {
          changed = true;
        }
      }
      if (graph.edges() == null) {
        continue;
      }
      for (ChainPlanEdge edge : graph.edges()) {
        if (reachable.contains(edge.fromNodeId()) && reachable.add(edge.toNodeId())) {
          changed = true;
        }
      }
    }
    return reachable;
  }

  private static ValidationIssue blocker(
      String issueId, String message, String ownerCapabilityId, List<String> affectedNodeIds) {
    return new ValidationIssue(
        issueId,
        ValidationSeverity.BLOCKER,
        message,
        ownerCapabilityId,
        List.copyOf(affectedNodeIds),
        List.of(),
        message);
  }
}
