package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.mapping.LegacyStageMappingAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
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
    List<RequirementServiceCall> serviceCalls = serviceCallsFrom(facts, brief.serviceCalls());
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

  /**
   * Projects outbound calls from facts. A supplied call is kept when its {@code serviceCallId} and
   * {@code sourceFactId} match the fact, including the frozen catalog binding. List order follows
   * the facts; it is not identity. Facts without a matching supplied call become unbound
   * compatibility records, which is also the path for a legacy brief that has facts but no
   * service-call list.
   */
  private static List<RequirementServiceCall> serviceCallsFrom(
      List<RequirementFact> facts, List<RequirementServiceCall> supplied) {
    List<RequirementFact> callFacts = positiveServiceCallFacts(facts);
    if (supplied == null || supplied.isEmpty()) {
      return unboundCallsFrom(callFacts);
    }
    Map<String, RequirementServiceCall> byId = indexSuppliedCalls(supplied);
    List<RequirementServiceCall> calls = new ArrayList<>();
    for (RequirementFact fact : callFacts) {
      RequirementServiceCall match = byId.get(fact.serviceCallId());
      if (match != null && match.sourceFactId().equals(fact.sourceFactId())) {
        calls.add(requireOwnedBinding(match));
      } else {
        calls.add(unboundCall(fact));
      }
    }
    return List.copyOf(calls);
  }

  private static List<RequirementFact> positiveServiceCallFacts(List<RequirementFact> facts) {
    List<RequirementFact> callFacts = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (fact == null || !fact.needsCatalogBinding()) {
        continue;
      }
      callFacts.add(fact);
    }
    return callFacts;
  }

  private static Map<String, RequirementServiceCall> indexSuppliedCalls(
      List<RequirementServiceCall> supplied) {
    Map<String, RequirementServiceCall> byId = new LinkedHashMap<>();
    for (RequirementServiceCall call : supplied) {
      if (call == null || call.serviceCallId().isBlank()) {
        throw new IllegalArgumentException("service call is missing serviceCallId");
      }
      RequirementServiceCall previous = byId.putIfAbsent(call.serviceCallId(), call);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate serviceCallId=" + call.serviceCallId());
      }
    }
    return byId;
  }

  private static RequirementServiceCall requireOwnedBinding(RequirementServiceCall call) {
    CatalogBindingHint hint = call.catalogBinding();
    if (hint != null && !call.serviceCallId().equals(hint.serviceCallId())) {
      throw new IllegalArgumentException(
          "catalog binding serviceCallId="
              + hint.serviceCallId()
              + " does not match call serviceCallId="
              + call.serviceCallId());
    }
    return call;
  }

  private static List<RequirementServiceCall> unboundCallsFrom(List<RequirementFact> callFacts) {
    List<RequirementServiceCall> calls = new ArrayList<>();
    for (RequirementFact fact : callFacts) {
      calls.add(unboundCall(fact));
    }
    return List.copyOf(calls);
  }

  private static RequirementServiceCall unboundCall(RequirementFact fact) {
    return new RequirementServiceCall(
        fact.serviceCallId(), fact.sourceFactId(), fact.participant(), fact.operation());
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
