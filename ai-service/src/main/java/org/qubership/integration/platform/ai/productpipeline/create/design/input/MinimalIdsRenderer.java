package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Deterministic minimal IDS renderer for DERIVE mode. Omits empty optional sections and generic
 * document prose.
 */
public final class MinimalIdsRenderer {

  public static final String RENDERER_VERSION = "minimal-ids-renderer@1";

  public String render(NormalizedDesignFlow flow) {
    Objects.requireNonNull(flow, "flow");
    StringBuilder out = new StringBuilder();
    out.append("# Integration Design Specification\n\n");
    out.append("## Integration Process\n\n");
    out.append("### Integration flow for CIP Chain - ")
        .append(flow.chainName())
        .append("\n\n");
    out.append("```mermaid\n");
    out.append("sequenceDiagram\n");
    out.append("    autonumber\n");
    for (NormalizedDesignFlow.Participant participant : flow.participants()) {
      String alias = mermaidAlias(participant.participantId());
      out.append("    participant ")
          .append(alias)
          .append(" as ")
          .append(participant.displayName())
          .append('\n');
    }
    for (NormalizedDesignFlow.Step step : flow.steps()) {
      if (step.fromParticipantId() == null || step.toParticipantId() == null) {
        continue;
      }
      out.append("    ")
          .append(mermaidAlias(step.fromParticipantId()))
          .append("->>")
          .append(mermaidAlias(step.toParticipantId()))
          .append(": ")
          .append(step.operationQuery() == null || step.operationQuery().isBlank()
              ? step.description()
              : step.operationQuery())
          .append('\n');
    }
    out.append("```\n\n");
    out.append("#### Process Steps\n\n");
    out.append("| Process Step | Description |\n");
    out.append("|--------------|-------------|\n");
    for (NormalizedDesignFlow.Step step : flow.steps()) {
      out.append("| ")
          .append(step.stepId())
          .append(" | ")
          .append(step.description().isBlank() ? step.operationQuery() : step.description())
          .append(" |\n");
    }
    if (flow.dataMappings() != null && !flow.dataMappings().isEmpty()) {
      List<NormalizedDesignFlow.DataMapping> explicit =
          flow.dataMappings().stream()
              .filter(mapping -> mapping.mode() == NormalizedDesignFlow.MappingMode.EXPLICIT)
              .toList();
      if (!explicit.isEmpty()) {
        out.append('\n');
        out.append("#### Data Mappings\n\n");
        out.append("| Mapping ID | From | To | Mode | Source Facts |\n");
        out.append("|------------|------|----|------|--------------|\n");
        for (NormalizedDesignFlow.DataMapping mapping : explicit) {
          out.append("| ")
              .append(mapping.mappingId())
              .append(" | ")
              .append(mapping.fromStepId())
              .append(" | ")
              .append(mapping.toStepId())
              .append(" | ")
              .append(mapping.mode().name())
              .append(" | ")
              .append(String.join(", ", mapping.sourceFactIds()))
              .append(" |\n");
        }
      }
    }
    if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
      out.append('\n');
    }
    return out.toString();
  }

  private static String mermaidAlias(String participantId) {
    return participantId.replace('-', '_');
  }
}
