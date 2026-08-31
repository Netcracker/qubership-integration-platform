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
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Fills v2 brief roles from captured facts and explicit mappings. Pass-through rows do not become
 * mapping intents. Field rules that share one source-to-target boundary stay on that intent.
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
      return collapseMappingIntents(brief);
    }
    return LegacyStageMappingAdapter.fromDataMappings(brief.dataMappings());
  }

  /**
   * Drops identity pass-through rows, then keeps one intent per catalog boundary. A field alias
   * captured as its own edge placeholder is folded into the boundary that already maps that field,
   * or into the unique call-to-call mapping when there is no token overlap.
   */
  public static List<MappingIntent> collapseMappingIntents(RequirementBrief brief) {
    if (brief == null || brief.mappingIntents().isEmpty()) {
      return List.of();
    }
    Topology topology = topologyOf(brief);
    List<MappingIntent> kept = collapsePassThroughIntents(brief.mappingIntents());
    kept = mergeSameBoundary(kept);
    kept = foldPlaceholders(kept, topology);
    return List.copyOf(kept);
  }

  private static List<MappingIntent> collapsePassThroughIntents(List<MappingIntent> intents) {
    List<MappingIntent> kept = new ArrayList<>();
    for (MappingIntent intent : intents) {
      if (intent == null || BriefMappingValidator.isIdentityOnlyAuto(intent.rules())) {
        continue;
      }
      kept.add(intent);
    }
    return kept;
  }

  private static List<MappingIntent> mergeSameBoundary(List<MappingIntent> intents) {
    Map<String, MappingIntent> byBoundary = new LinkedHashMap<>();
    for (MappingIntent intent : intents) {
      String key = boundaryKey(intent);
      MappingIntent previous = byBoundary.get(key);
      byBoundary.put(key, previous == null ? intent : mergeRules(previous, intent));
    }
    return new ArrayList<>(byBoundary.values());
  }

  private static List<MappingIntent> foldPlaceholders(
      List<MappingIntent> intents, Topology topology) {
    List<MappingIntent> anchored = new ArrayList<>();
    List<MappingIntent> placeholders = new ArrayList<>();
    for (MappingIntent intent : intents) {
      if (isPlaceholder(intent, topology)) {
        placeholders.add(intent);
      } else {
        anchored.add(intent);
      }
    }
    for (MappingIntent placeholder : placeholders) {
      int overlap = uniqueOverlapIndex(anchored, placeholder);
      if (overlap >= 0) {
        anchored.set(overlap, mergeRules(anchored.get(overlap), placeholder));
        continue;
      }
      int callToCall = uniqueCallToCallIndex(anchored, topology.callIds);
      if (callToCall >= 0) {
        anchored.set(callToCall, mergeRules(anchored.get(callToCall), placeholder));
        continue;
      }
      anchored.add(placeholder);
    }
    return anchored;
  }

  private static boolean isPlaceholder(MappingIntent intent, Topology topology) {
    if (!intent.sourceRef().isBlank() && intent.sourceRef().equals(intent.targetRef())) {
      return true;
    }
    if (topology.ids.isEmpty()) {
      return false;
    }
    return !topology.ids.contains(intent.sourceRef())
        || !topology.ids.contains(intent.targetRef());
  }

  private static int uniqueOverlapIndex(List<MappingIntent> anchored, MappingIntent placeholder) {
    Set<String> tokens = targetPathTokens(placeholder);
    int found = -1;
    for (int i = 0; i < anchored.size(); i++) {
      if (!sharesTargetPathToken(anchored.get(i), tokens)) {
        continue;
      }
      if (found >= 0) {
        return -1;
      }
      found = i;
    }
    return found;
  }

  private static int uniqueCallToCallIndex(List<MappingIntent> anchored, Set<String> callIds) {
    int found = -1;
    for (int i = 0; i < anchored.size(); i++) {
      MappingIntent intent = anchored.get(i);
      if (!callIds.contains(intent.sourceRef()) || !callIds.contains(intent.targetRef())) {
        continue;
      }
      if (found >= 0) {
        return -1;
      }
      found = i;
    }
    return found;
  }

  private static MappingIntent mergeRules(MappingIntent into, MappingIntent extra) {
    List<MappingIntentRule> rules = new ArrayList<>(into.rules());
    Set<String> seen = new LinkedHashSet<>();
    for (MappingIntentRule rule : into.rules()) {
      seen.add(ruleKey(rule));
    }
    for (MappingIntentRule rule : extra.rules()) {
      if (seen.add(ruleKey(rule))) {
        rules.add(rule);
      }
    }
    return into.withRules(List.copyOf(rules));
  }

  private static String boundaryKey(MappingIntent intent) {
    return intent.sourceRef()
        + '\0'
        + portKey(intent.sourcePort())
        + '\0'
        + intent.targetRef()
        + '\0'
        + portKey(intent.targetPort());
  }

  private static String portKey(MappingPort port) {
    return port == null ? "" : port.name();
  }

  private static String ruleKey(MappingIntentRule rule) {
    return rule.sourcePath()
        + '\0'
        + rule.targetPath()
        + '\0'
        + (rule.expression() == null ? "" : rule.expression());
  }

  private static Set<String> targetPathTokens(MappingIntent intent) {
    Set<String> tokens = new LinkedHashSet<>();
    for (MappingIntentRule rule : intent.rules()) {
      addPathTokens(tokens, rule.targetPath());
    }
    return tokens;
  }

  private static void addPathTokens(Set<String> tokens, String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    for (String part : path.split(",")) {
      String token = part.trim();
      if (!token.isEmpty()) {
        tokens.add(token);
      }
    }
  }

  private static boolean sharesTargetPathToken(MappingIntent intent, Set<String> tokens) {
    for (String token : targetPathTokens(intent)) {
      if (tokens.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private static Topology topologyOf(RequirementBrief brief) {
    Set<String> ids = new LinkedHashSet<>();
    Set<String> callIds = new LinkedHashSet<>();
    for (RequirementFact fact : brief.facts()) {
      if (fact == null) {
        continue;
      }
      rememberId(ids, fact.sourceFactId());
      rememberId(ids, fact.serviceCallId());
      if (fact.kind() == RequirementFactKind.SERVICE_CALL) {
        rememberId(callIds, fact.serviceCallId());
        rememberId(callIds, fact.sourceFactId());
      }
    }
    for (RequirementServiceCall call : brief.serviceCalls()) {
      if (call == null) {
        continue;
      }
      rememberId(ids, call.serviceCallId());
      rememberId(ids, call.sourceFactId());
      rememberId(callIds, call.serviceCallId());
    }
    for (RequirementEntryPoint entryPoint : brief.entryPoints()) {
      if (entryPoint == null) {
        continue;
      }
      rememberId(ids, entryPoint.entryPointId());
      rememberId(ids, entryPoint.sourceFactId());
    }
    return new Topology(ids, callIds);
  }

  private static void rememberId(Set<String> ids, String value) {
    if (value != null && !value.isBlank()) {
      ids.add(value.trim());
    }
  }

  private record Topology(Set<String> ids, Set<String> callIds) {}

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
