package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Design-path coverage for unique service-call ids and a missing trigger. Mapping sockets are
 * locked later by {@code RequirementBriefCoverageValidator}; this type does not iterate mapping
 * rows.
 */
public final class DesignRequirementBriefCoverageValidator {

  public void validate(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    requireUniqueServiceCallSteps(brief);
    List<RequirementFact> outboundCalls = positiveFacts(brief, RequirementFactKind.SERVICE_CALL);
    boolean hasOutbound = !outboundCalls.isEmpty() || !brief.serviceCalls().isEmpty();
    if (hasOutbound
        && brief.entryPoints().isEmpty()
        && !brief.flow().interactions().isEmpty()) {
      throw new IllegalArgumentException(
          "Requirement brief is missing a configured trigger entry. Capture a trigger before"
              + " mapping validation.");
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
   * Mapping rows are no longer a design-coverage concern. Returns the brief unchanged.
   */
  public RequirementBrief retainTopologyBoundMappings(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    return brief;
  }

  /**
   * Does not invent pass-through rows. Returns the brief unchanged.
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

  private static List<RequirementFact> positiveFacts(
      RequirementBrief brief, RequirementFactKind kind) {
    return brief.facts().stream()
        .filter(fact -> fact != null)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.kind() == kind)
        .toList();
  }
}
