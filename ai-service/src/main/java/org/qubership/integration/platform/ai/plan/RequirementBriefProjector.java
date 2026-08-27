package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.mapping.LegacyStageMappingAdapter;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
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
        mappingIntentsFor(brief));
  }

  private static List<MappingIntent> mappingIntentsFor(RequirementBrief brief) {
    if (!brief.mappingIntents().isEmpty()) {
      return collapsePassThroughIntents(brief.mappingIntents());
    }
    return LegacyStageMappingAdapter.fromDataMappings(brief.dataMappings());
  }

  private static List<MappingIntent> collapsePassThroughIntents(List<MappingIntent> intents) {
    List<MappingIntent> kept = new ArrayList<>();
    for (MappingIntent intent : intents) {
      if (intent == null || BriefMappingValidator.isIdentityOnlyAuto(intent.rules())) {
        continue;
      }
      kept.add(intent);
    }
    return List.copyOf(kept);
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
}
