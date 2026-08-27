package org.qubership.integration.platform.ai.qipknowledge.artifact;

import java.util.List;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;

/** Renders a structured {@link RequirementBrief} for downstream compiler prompts. */
public final class RequirementBriefText {

  private RequirementBriefText() {}

  public static String format(RequirementBrief brief) {
    if (brief == null) {
      return "";
    }
    StringBuilder body = new StringBuilder();
    appendLine(body, "Goal", brief.goal());
    appendLine(body, "Summary", brief.summary());
    appendList(body, "Inputs", brief.inputs());
    appendList(body, "Constraints", brief.constraints());
    appendList(body, "Assumptions", brief.assumptions());
    appendFacts(body, brief.facts());
    appendEntryPoints(body, brief.entryPoints());
    appendServiceCalls(body, brief.serviceCalls());
    appendDataMappings(body, brief.dataMappings());
    appendMappingIntents(body, brief.mappingIntents());
    return body.toString().trim();
  }

  private static void appendEntryPoints(
      StringBuilder body, List<RequirementEntryPoint> entryPoints) {
    if (entryPoints == null || entryPoints.isEmpty()) {
      return;
    }
    if (!body.isEmpty()) {
      body.append('\n');
    }
    body.append("Entry points:");
    for (var entryPoint : entryPoints) {
      body.append('\n')
          .append("- ")
          .append(entryPoint.capabilityKey())
          .append(" [")
          .append(entryPoint.entryPointId())
          .append(']');
    }
  }

  private static void appendServiceCalls(
      StringBuilder body, List<RequirementServiceCall> serviceCalls) {
    if (serviceCalls == null || serviceCalls.isEmpty()) {
      return;
    }
    if (!body.isEmpty()) {
      body.append('\n');
    }
    body.append("Service calls:");
    for (var serviceCall : serviceCalls) {
      body.append('\n')
          .append("- ")
          .append(serviceCall.participant())
          .append(": ")
          .append(serviceCall.operation());
    }
  }

  private static void appendMappingIntents(StringBuilder body, List<MappingIntent> intents) {
    if (intents == null || intents.isEmpty()) {
      return;
    }
    if (!body.isEmpty()) {
      body.append('\n');
    }
    body.append("Mapping intents:");
    for (var intent : intents) {
      body.append('\n')
          .append("- ")
          .append(intent.mappingIntentId())
          .append(' ')
          .append(intent.sourceRef())
          .append('/')
          .append(intent.sourcePort())
          .append(" -> ")
          .append(intent.targetRef())
          .append('/')
          .append(intent.targetPort());
      for (MappingIntentRule rule : intent.rules()) {
        body.append('\n')
            .append("  - ")
            .append(rule.status())
            .append(' ')
            .append(rule.sourcePath())
            .append(" -> ")
            .append(rule.targetPath());
        if (rule.expression() != null) {
          body.append(" | expression: ").append(rule.expression());
        }
      }
    }
  }

  private static void appendDataMappings(
      StringBuilder body, List<RequirementDataMapping> mappings) {
    if (mappings == null || mappings.isEmpty()) {
      return;
    }
    if (!body.isEmpty()) {
      body.append('\n');
    }
    body.append("Data mappings:");
    for (RequirementDataMapping mapping : mappings) {
      if (mapping == null) {
        continue;
      }
      body.append('\n')
          .append("- ")
          .append(mapping.mappingId())
          .append(" [")
          .append(mapping.stage())
          .append(", ")
          .append(mapping.mode())
          .append("] ")
          .append(mapping.fromIntentRef())
          .append(" -> ")
          .append(mapping.toIntentRef());
      for (RequirementDataMapping.Rule rule : mapping.rules()) {
        body.append('\n')
            .append("  - ")
            .append(rule.sourcePath())
            .append(" -> ")
            .append(rule.targetPath());
        if (rule.expression() != null) {
          body.append(" | expression: ").append(rule.expression());
        }
      }
    }
  }

  private static void appendFacts(StringBuilder body, List<RequirementFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return;
    }
    if (!body.isEmpty()) {
      body.append('\n');
    }
    body.append("Facts:");
    for (RequirementFact fact : facts) {
      if (fact == null || fact.text() == null || fact.text().isBlank()) {
        continue;
      }
      String prefix =
          fact.polarity() == RequirementFactPolarity.NEGATIVE ? "[NEGATIVE] " : "[POSITIVE] ";
      body.append('\n').append("- ").append(prefix).append(fact.text().trim());
    }
  }

  private static void appendLine(StringBuilder body, String label, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (!body.isEmpty()) {
      body.append('\n');
    }
    body.append(label).append(": ").append(value.trim());
  }

  private static void appendList(StringBuilder body, String label, List<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    if (!body.isEmpty()) {
      body.append('\n');
    }
    body.append(label).append(':');
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      body.append('\n').append("- ").append(value.trim());
    }
  }
}
