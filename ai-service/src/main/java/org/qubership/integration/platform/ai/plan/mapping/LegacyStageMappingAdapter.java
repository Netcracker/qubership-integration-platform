package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Temporary seam from stage-based {@link RequirementDataMapping} rows onto port-based {@link
 * MappingIntent}s. Pass-through is the absence of an intent, not a synthetic v2 row. New planning
 * and compiler validation read ports and {@code mappingIntentId}, not {@code INITIALIZATION},
 * {@code CONVERSION}, or {@code RESPONSE}.
 */
public final class LegacyStageMappingAdapter {

  private LegacyStageMappingAdapter() {}

  /**
   * Fills {@code mappingIntents} from legacy {@code dataMappings} when the brief still has no v2
   * intents. Existing intents are left unchanged.
   */
  public static RequirementBrief ensureIntents(RequirementBrief brief) {
    if (brief == null || !brief.mappingIntents().isEmpty()) {
      return brief;
    }
    return brief.withMappingIntents(fromDataMappings(brief.dataMappings()));
  }

  public static List<MappingIntent> fromDataMappings(List<RequirementDataMapping> mappings) {
    if (mappings == null || mappings.isEmpty()) {
      return List.of();
    }
    List<MappingIntent> intents = new ArrayList<>();
    for (RequirementDataMapping mapping : mappings) {
      MappingIntent intent = toIntent(mapping);
      if (intent != null) {
        intents.add(intent);
      }
    }
    return List.copyOf(intents);
  }

  private static MappingIntent toIntent(RequirementDataMapping mapping) {
    if (mapping == null || mapping.mode() != RequirementDataMapping.Mode.EXPLICIT) {
      return null;
    }
    if (mapping.stage() == null) {
      return null;
    }
    List<MappingIntentRule> rules = BriefMappingValidator.classifyFromLegacy(mapping.rules());
    if (BriefMappingValidator.isIdentityOnlyAuto(rules)) {
      return null;
    }
    return new MappingIntent(
        mapping.mappingId(),
        mapping.fromIntentRef(),
        sourcePort(mapping.stage()),
        mapping.toIntentRef(),
        targetPort(mapping.stage()),
        rules);
  }

  private static MappingPort sourcePort(RequirementDataMapping.Stage stage) {
    if (stage == RequirementDataMapping.Stage.INITIALIZATION) {
      return MappingPort.OUTPUT;
    }
    return MappingPort.RESPONSE;
  }

  private static MappingPort targetPort(RequirementDataMapping.Stage stage) {
    if (stage == RequirementDataMapping.Stage.RESPONSE) {
      return MappingPort.OUTPUT;
    }
    return MappingPort.REQUEST;
  }
}
