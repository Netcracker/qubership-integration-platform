package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Fills v2 brief roles from captured facts and explicit mappings. Pass-through rows do not become
 * mapping intents.
 */
public final class RequirementBriefProjector {

  private RequirementBriefProjector() {}

  public static RequirementBrief project(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    List<RequirementFact> facts = brief.facts();
    List<RequirementEntryPoint> entryPoints = entryPointsFrom(facts);
    List<RequirementServiceCall> serviceCalls = serviceCallsFrom(facts);
    return new RequirementBrief(
        brief.goal(),
        brief.inputs(),
        brief.constraints(),
        brief.assumptions(),
        brief.citations(),
        brief.summary(),
        brief.approvedDraftReference(),
        brief.approvedDraftText(),
        facts,
        brief.dataMappings(),
        entryPoints,
        serviceCalls,
        requirementsFrom(facts, entryPoints, serviceCalls),
        mappingIntentsFrom(brief.dataMappings()));
  }

  static List<RequirementEntryPoint> entryPointsFrom(List<RequirementFact> facts) {
    List<RequirementEntryPoint> entryPoints = new ArrayList<>();
    for (RequirementFact fact : RequirementTriggerRole.positiveTriggers(facts)) {
      entryPoints.add(
          new RequirementEntryPoint(
              fact.sourceFactId(),
              fact.sourceFactId(),
              fact.capabilityKey(),
              fact.topic(),
              fact.httpMethod(),
              fact.path(),
              fact.operation()));
    }
    return List.copyOf(entryPoints);
  }

  private static List<RequirementServiceCall> serviceCallsFrom(List<RequirementFact> facts) {
    List<RequirementServiceCall> calls = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (fact == null
          || fact.polarity() != RequirementFactPolarity.POSITIVE
          || fact.kind() != RequirementFactKind.SERVICE_CALL) {
        continue;
      }
      calls.add(
          new RequirementServiceCall(
              fact.sourceFactId(),
              fact.sourceFactId(),
              fact.participant(),
              fact.operation()));
    }
    return List.copyOf(calls);
  }

  private static List<RequirementFact> requirementsFrom(
      List<RequirementFact> facts,
      List<RequirementEntryPoint> entryPoints,
      List<RequirementServiceCall> serviceCalls) {
    Set<String> claimed = new LinkedHashSet<>();
    for (RequirementEntryPoint entryPoint : entryPoints) {
      claimed.add(entryPoint.sourceFactId());
    }
    for (RequirementServiceCall serviceCall : serviceCalls) {
      claimed.add(serviceCall.sourceFactId());
    }
    List<RequirementFact> requirements = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (fact == null || claimed.contains(fact.sourceFactId())) {
        continue;
      }
      requirements.add(fact);
    }
    return List.copyOf(requirements);
  }

  private static List<MappingIntent> mappingIntentsFrom(List<RequirementDataMapping> mappings) {
    if (mappings == null || mappings.isEmpty()) {
      return List.of();
    }
    List<MappingIntent> intents = new ArrayList<>();
    for (RequirementDataMapping mapping : mappings) {
      if (mapping == null || mapping.mode() != RequirementDataMapping.Mode.EXPLICIT) {
        continue;
      }
      intents.add(
          new MappingIntent(
              mapping.mappingId(),
              mapping.fromIntentRef(),
              sourcePort(mapping.stage()),
              mapping.toIntentRef(),
              targetPort(mapping.stage()),
              mapping.rules()));
    }
    return List.copyOf(intents);
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
