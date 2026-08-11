package org.qubership.integration.platform.ai.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

public final class OwnedSchemaRequiredPropertyGate {

  static final String PLACEHOLDER_VALUE = "<set required value>";
  static final String PLACEHOLDER_CRON = "<set required cron>";
  private static final Set<String> KNOWN_PLACEHOLDER_SENTINELS =
      Set.of(PLACEHOLDER_VALUE, PLACEHOLDER_CRON);
  private static final java.util.regex.Pattern ANGLE_BRACKET_PLACEHOLDER =
      java.util.regex.Pattern.compile("^<[^>]+>$");

  private OwnedSchemaRequiredPropertyGate() {}

  public record Gap(String nodeId, String elementType, List<String> missingPropertyKeys) {}

  public static List<Gap> findGaps(
      ChainPlanGraph graph,
      GraphPatchOwnershipPolicy ownership,
      Function<String, Set<String>> unconditionalRequiredForType) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(ownership, "ownership");
    Objects.requireNonNull(unconditionalRequiredForType, "unconditionalRequiredForType");

    List<Gap> gaps = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.type() == null) {
        continue;
      }
      Set<String> owned = ownership.properties().get(node.type());
      if (owned == null || owned.isEmpty()) {
        continue;
      }
      Set<String> schemaRequired = unconditionalRequiredForType.apply(node.type());
      if (schemaRequired == null || schemaRequired.isEmpty()) {
        continue;
      }
      LinkedHashSet<String> required = new LinkedHashSet<>();
      for (String key : schemaRequired) {
        if (owned.contains(key)) {
          required.add(key);
        }
      }
      if (required.isEmpty()) {
        continue;
      }
      List<String> missing = new ArrayList<>();
      for (String key : required) {
        if (!isPresent(node, key)) {
          missing.add(key);
        }
      }
      if (!missing.isEmpty()) {
        gaps.add(new Gap(node.nodeId(), node.type(), List.copyOf(missing)));
      }
    }
    return List.copyOf(gaps);
  }

  public static String formatCorrectableMessage(String capabilityId, List<Gap> gaps) {
    Objects.requireNonNull(gaps, "gaps");
    StringBuilder sb = new StringBuilder();
    sb.append("Owned schema required properties missing for capability '")
        .append(capabilityId == null ? "" : capabilityId)
        .append("'.\n");
    for (Gap gap : gaps) {
      sb.append("- nodeId=")
          .append(gap.nodeId())
          .append(" (")
          .append(gap.elementType())
          .append("): missing ")
          .append(gap.missingPropertyKeys())
          .append('\n');
    }
    sb.append("Submit a captureGraphPatch with propertyPatches shaped like:\n[\n");
    boolean first = true;
    for (Gap gap : gaps) {
      for (String key : gap.missingPropertyKeys()) {
        if (!first) {
          sb.append(",\n");
        }
        first = false;
        sb.append("  {\n")
            .append("    \"operation\": \"ADD\",\n")
            .append("    \"targetNodeId\": \"")
            .append(gap.nodeId())
            .append("\",\n")
            .append("    \"key\": \"")
            .append(key)
            .append("\",\n")
            .append("    \"value\": \"\"\n")
            .append("  }");
      }
    }
    sb.append("\n]\n");
    boolean cronOnly =
        gaps.stream().flatMap(g -> g.missingPropertyKeys().stream()).allMatch("cron"::equals);
    if (cronOnly) {
      sb.append(
          "Replace empty value with a real cron expression matching the schedule intent; "
              + "do not use placeholder tokens.");
    } else {
      sb.append(
          "Replace empty value with a real value from the requirement brief; "
              + "do not use placeholder tokens.");
    }
    return sb.toString();
  }

  private static boolean isPresent(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return false;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && key.equals(property.key())) {
        return isRealValue(property.value());
      }
    }
    return false;
  }

  /** True when the value is non-blank and not a known placeholder / angle-bracket token. */
  public static boolean isRealValue(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String trimmed = value.trim();
    if (KNOWN_PLACEHOLDER_SENTINELS.contains(trimmed)) {
      return false;
    }
    return !ANGLE_BRACKET_PLACEHOLDER.matcher(trimmed).matches();
  }
}
