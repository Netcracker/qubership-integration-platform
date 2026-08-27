package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * Specialized-generator seam: write Groovy onto an existing script shell. Does not add nodes or
 * edges.
 */
public final class ScriptConfigurationPhase {

  private ScriptConfigurationPhase() {}

  public static ChainPlanGraph configure(ChainPlanGraph graph, RequirementBrief brief) {
    if (graph == null) {
      throw new IllegalArgumentException("graph is required");
    }
    if (brief == null || brief.mappingIntents().isEmpty()) {
      return graph;
    }
    ChainPlanGraph current = graph;
    for (MappingIntent intent : brief.mappingIntents()) {
      if (!isScriptIntent(intent)) {
        continue;
      }
      ChainPlanNode site = requireSite(current, intent.mappingIntentId());
      current =
          current.withNodeProperty(site.nodeId(), MappingExecutionSite.SCRIPT_PROPERTY, generateScript(intent));
    }
    return current;
  }

  public static GraphPatch configurationPatch(ChainPlanGraph graph, RequirementBrief brief) {
    if (graph == null) {
      throw new IllegalArgumentException("graph is required");
    }
    List<PropertyPatch> propertyPatches = new ArrayList<>();
    List<MappingIntent> intents = brief == null ? List.of() : brief.mappingIntents();
    for (MappingIntent intent : intents) {
      if (!isScriptIntent(intent)) {
        continue;
      }
      ChainPlanNode site = requireSite(graph, intent.mappingIntentId());
      propertyPatches.add(
          new PropertyPatch(
              GraphPatchOperation.ADD,
              site.nodeId(),
              new PlanProperty(MappingExecutionSite.SCRIPT_PROPERTY, generateScript(intent))));
    }
    return new GraphPatch(
        "configure-script",
        "cip-script-generator",
        List.of(),
        List.of(),
        List.copyOf(propertyPatches),
        List.of(),
        List.of(),
        "Configure existing script shells from approved mapping intents");
  }

  static String generateScript(MappingIntent intent) {
    StringBuilder body = new StringBuilder();
    body.append("def source = new groovy.json.JsonSlurper().parseText(exchange.in.body as String)\n");
    body.append("def target = [:]\n");
    for (MappingIntentRule rule : intent.rules()) {
      String targetAccess = targetAccess(rule.targetPath());
      body.append(targetAccess).append(" = ").append(rightHandSide(rule)).append('\n');
    }
    body.append("exchange.in.body = new groovy.json.JsonBuilder(target).toString()\n");
    return body.toString();
  }

  private static boolean isScriptIntent(MappingIntent intent) {
    return intent != null
        && !intent.mappingIntentId().isBlank()
        && MappingMechanismSelector.select(intent).orElse(null) == MappingMechanism.SCRIPT;
  }

  private static ChainPlanNode requireSite(ChainPlanGraph graph, String mappingIntentId) {
    for (ChainPlanNode node : graph.nodes()) {
      if (mappingIntentId.equals(MappingExecutionSite.mappingIntentId(node))) {
        return node;
      }
    }
    throw new IllegalStateException(
        "Transform shell for mapping intent '"
            + mappingIntentId
            + "' is missing. Structure generation must insert the script site before"
            + " cip-script-generator configures it.");
  }

  private static String rightHandSide(MappingIntentRule rule) {
    if (rule.expression() != null) {
      return sourceAccess(rule.sourcePath()) + transformSuffix(rule.expression());
    }
    if (MappingMechanismSelector.isConstantLiteral(rule.sourcePath())) {
      return "'"
          + MappingMechanismSelector.constantValue(rule.sourcePath()).replace("'", "\\'")
          + "'";
    }
    return sourceAccess(rule.sourcePath());
  }

  private static String transformSuffix(String expression) {
    String normalized = expression.toLowerCase(Locale.ROOT);
    if (normalized.contains("uppercase") || normalized.contains("touppercase")) {
      return "?.toString()?.toUpperCase()";
    }
    if (normalized.contains("lowercase") || normalized.contains("tolowercase")) {
      return "?.toString()?.toLowerCase()";
    }
    return "?.toString()?.trim()";
  }

  private static String sourceAccess(String jsonPath) {
    StringBuilder access = new StringBuilder("source");
    for (String segment : segments(jsonPath)) {
      access.append("['").append(segment.replace("'", "\\'")).append("']");
    }
    return access.toString();
  }

  private static String targetAccess(String jsonPath) {
    StringBuilder access = new StringBuilder("target");
    for (String segment : segments(jsonPath)) {
      access.append("['").append(segment.replace("'", "\\'")).append("']");
    }
    return access.toString();
  }

  private static String[] segments(String path) {
    String trimmed = path == null ? "" : path.trim();
    if (trimmed.startsWith("$.")) {
      trimmed = trimmed.substring(2);
    } else if (trimmed.startsWith("$")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.startsWith(".")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.isEmpty()) {
      return new String[0];
    }
    return trimmed.split("\\.");
  }
}
