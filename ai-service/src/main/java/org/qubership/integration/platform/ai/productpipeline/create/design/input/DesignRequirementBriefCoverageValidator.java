package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Design-path ({@code create-chain@2}) coverage for leftover mapping rows on a requirement brief.
 * Coverage does not infer {@code INITIALIZATION}, {@code CONVERSION}, or {@code RESPONSE} edges
 * from trigger and service-call order. Missing topology is not a mapping gap. Incomplete rows are
 * dropped first; {@link #validate(RequirementBrief)} checks remaining well-shaped mappings.
 * Unknown source or target schemas do not require a mapping row.
 */
public final class DesignRequirementBriefCoverageValidator {

  public void validate(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    RequirementBrief normalized = DesignRequirementDataMappingNormalizer.normalize(brief);
    requireUniqueServiceCallSteps(normalized);
    List<RequirementFact> outboundCalls =
        positiveFacts(normalized, RequirementFactKind.SERVICE_CALL);
    boolean hasOutbound = !outboundCalls.isEmpty() || !normalized.serviceCalls().isEmpty();
    if (hasOutbound
        && normalized.entryPoints().isEmpty()
        && !normalized.flow().interactions().isEmpty()) {
      throw new IllegalArgumentException(
          "Requirement brief is missing a configured trigger entry. Capture a trigger before"
              + " mapping validation.");
    }
    for (RequirementDataMapping mapping : normalized.dataMappings()) {
      validateMappingShape(mapping);
    }
  }

  /**
   * Stage-ordered mapping holes are not a coverage gap. Returns empty so callers cannot block
   * approval by inventing {@code INITIALIZATION}/{@code CONVERSION}/{@code RESPONSE} edges.
   */
  public List<String> listMissingEdges(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    return List.of();
  }

  /**
   * Same as {@link #listMissingEdges(RequirementBrief)}: no inferred stage edges to show.
   */
  public List<String> listReadableMissingEdges(RequirementBrief brief) {
    return listMissingEdges(brief);
  }

  /**
   * Drops mapping rows whose intent refs are not the captured trigger or service-call facts.
   * Does not synthesize pass-through rows.
   */
  public RequirementBrief retainTopologyBoundMappings(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    RequirementBrief normalized = DesignRequirementDataMappingNormalizer.normalize(brief);
    return withMappings(normalized, topologyBoundMappings(normalized));
  }

  /**
   * Does not invent {@code PASS_THROUGH} rows for inferred stage holes. Drops unbound leftover
   * mappings and leaves pass-through as the absence of a mapping intent.
   */
  public RequirementBrief withPassThroughForMissingEdges(RequirementBrief brief) {
    return retainTopologyBoundMappings(brief);
  }

  private static void requireUniqueServiceCallSteps(RequirementBrief brief) {
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    if (!brief.serviceCalls().isEmpty()) {
      for (RequirementServiceCall call : brief.serviceCalls()) {
        if (call == null || call.serviceCallId() == null || call.serviceCallId().isBlank()) {
          throw new IllegalArgumentException("service call is missing serviceCallId");
        }
        rememberUniqueCallId(seen, call.serviceCallId());
      }
      return;
    }
    for (RequirementFact fact : positiveFacts(brief, RequirementFactKind.SERVICE_CALL)) {
      String id =
          fact.serviceCallId() == null || fact.serviceCallId().isBlank()
              ? fact.sourceFactId()
              : fact.serviceCallId();
      rememberUniqueCallId(seen, id);
    }
  }

  private static void rememberUniqueCallId(Set<String> seen, String serviceCallId) {
    if (!seen.add(serviceCallId)) {
      throw new IllegalArgumentException(
          "serviceCallId=" + serviceCallId + " does not map to a unique service-call step");
    }
  }

  private static List<RequirementDataMapping> topologyBoundMappings(RequirementBrief brief) {
    Set<String> topologyIds = new LinkedHashSet<>();
    for (RequirementEntryPoint entryPoint : brief.entryPoints()) {
      if (entryPoint == null) {
        continue;
      }
      topologyIds.add(entryPoint.entryPointId());
      topologyIds.add(entryPoint.sourceFactId());
    }
    for (RequirementServiceCall call : brief.serviceCalls()) {
      if (call == null) {
        continue;
      }
      topologyIds.add(call.serviceCallId());
      topologyIds.add(call.sourceFactId());
    }
    if (topologyIds.isEmpty()) {
      for (RequirementFact fact : positiveFacts(brief, RequirementFactKind.ENDPOINT)) {
        topologyIds.add(fact.sourceFactId());
      }
      for (RequirementFact fact : positiveFacts(brief, RequirementFactKind.SERVICE_CALL)) {
        topologyIds.add(fact.sourceFactId());
      }
    }
    return DesignRequirementDataMappingNormalizer.completeMappings(brief.dataMappings()).stream()
        .filter(
            mapping ->
                topologyIds.contains(mapping.fromIntentRef())
                    && topologyIds.contains(mapping.toIntentRef()))
        .toList();
  }

  private static RequirementBrief withMappings(
      RequirementBrief brief, List<RequirementDataMapping> mappings) {
    return RequirementBriefProjector.project(brief.withDataMappings(mappings));
  }

  private static void validateMappingShape(RequirementDataMapping mapping) {
    if (mapping == null) {
      throw new IllegalArgumentException("dataMappings must not contain null entries");
    }
    if (mapping.stage() == null) {
      throw new IllegalArgumentException("dataMapping stage is required");
    }
    if (mapping.mode() == null) {
      throw new IllegalArgumentException("dataMapping mode is required");
    }
    switch (mapping.mode()) {
      case EXPLICIT -> {
        if (mapping.rules() == null || mapping.rules().isEmpty()) {
          throw new IllegalArgumentException("EXPLICIT mapping requires at least one rule");
        }
        for (RequirementDataMapping.Rule rule : mapping.rules()) {
          if (rule == null
              || rule.sourcePath() == null
              || rule.sourcePath().isBlank()
              || rule.targetPath() == null
              || rule.targetPath().isBlank()) {
            throw new IllegalArgumentException(
                "EXPLICIT mapping rule requires sourcePath and targetPath");
          }
        }
      }
      case PASS_THROUGH -> {
        if (mapping.rules() != null && !mapping.rules().isEmpty()) {
          throw new IllegalArgumentException(
              "PASS_THROUGH mapping must not declare rules; cite a sourceFactId for pass-through"
                  + " intent");
        }
        if (mapping.sourceFactIds() == null || mapping.sourceFactIds().isEmpty()) {
          throw new IllegalArgumentException(
              "PASS_THROUGH mapping requires at least one sourceFactId");
        }
      }
    }
  }

  private static List<RequirementFact> positiveFacts(
      RequirementBrief brief, RequirementFactKind kind) {
    return brief.facts().stream()
        .filter(fact -> fact != null)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.kind() == kind)
        .toList();
  }
}
