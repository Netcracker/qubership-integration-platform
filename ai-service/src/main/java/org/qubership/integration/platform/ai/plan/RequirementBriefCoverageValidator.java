package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignRequirementBriefCoverageValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/**
 * Compares approved-draft fact IDs to the captured brief. Never invents facts from transcript.
 * Empty draft facts are a coverage no-op (catalog-import promotion may approve without facts).
 */
public final class RequirementBriefCoverageValidator {

  private final DesignRequirementBriefCoverageValidator topologyValidator =
      new DesignRequirementBriefCoverageValidator();

  public Optional<String> validate(RequirementDraft approvedDraft, RequirementBrief brief) {
    Objects.requireNonNull(approvedDraft, "approvedDraft");
    Objects.requireNonNull(brief, "brief");

    List<RequirementFact> draftFacts = approvedDraft.facts();
    List<RequirementFact> briefFacts = brief.facts();
    if (!approvedDraft.flow().equals(brief.flow())) {
      return Optional.of("requirement brief flow does not match the approved draft");
    }
    Optional<String> mappingRoleError = validateMappingRoles(brief);
    if (mappingRoleError.isPresent()) {
      return mappingRoleError;
    }
    Optional<String> projectedRoleError = validateProjectedRoles(brief);
    if (projectedRoleError.isPresent()) {
      return projectedRoleError;
    }
    // Catalog import may promote a draft to READY_FOR_PLAN before gather
    // distilled explicit facts — typically when the turn only captured an APIHub candidate.
    // Fact coverage is then a no-op: there are no sourceFactId values to pin or mismatch.
    if (draftFacts.isEmpty()) {
      return Optional.empty();
    }
    if (briefFacts.isEmpty()) {
      return Optional.of("requirement brief has no normalized facts");
    }

    Map<String, RequirementFact> draftById = indexById(draftFacts, "approved draft");
    if (draftById.size() != draftFacts.size()) {
      return Optional.of("approved draft contains duplicate sourceFactId values");
    }
    Map<String, RequirementFact> briefById = indexById(briefFacts, "requirement brief");
    if (briefById.size() != briefFacts.size()) {
      return Optional.of("requirement brief contains duplicate sourceFactId values");
    }

    Set<String> missing = new LinkedHashSet<>(draftById.keySet());
    missing.removeAll(briefById.keySet());
    if (!missing.isEmpty()) {
      return Optional.of("requirement brief missing sourceFactId values: " + String.join(", ", missing));
    }

    Set<String> extra = new LinkedHashSet<>(briefById.keySet());
    extra.removeAll(draftById.keySet());
    if (!extra.isEmpty()) {
      return Optional.of(
          "requirement brief contains sourceFactId values absent from approved draft: "
              + String.join(", ", extra));
    }

    List<String> polarityMismatches = new ArrayList<>();
    for (String id : draftById.keySet()) {
      RequirementFact draftFact = draftById.get(id);
      RequirementFact briefFact = briefById.get(id);
      if (draftFact.polarity() != briefFact.polarity()) {
        polarityMismatches.add(id);
      }
    }
    if (!polarityMismatches.isEmpty()) {
      return Optional.of(
          "requirement brief changed polarity for sourceFactId values: "
              + String.join(", ", polarityMismatches));
    }

    if (brief.approvedDraftText() != null
        && !brief.approvedDraftText().isBlank()
        && !brief.approvedDraftText().equals(approvedDraft.planningText())) {
      return Optional.of("requirement brief approvedDraftText does not match approved draft");
    }
    if (!approvedDraft.flow().equals(brief.flow())) {
      return Optional.of("requirement brief flow does not match the approved draft");
    }
    Optional<String> mappingError = validateCapturedMappings(approvedDraft, brief);
    if (mappingError.isPresent()) {
      return mappingError;
    }
    Optional<String> serviceCallError = validateServiceCalls(brief);
    if (serviceCallError.isPresent()) {
      return serviceCallError;
    }
    if (isSingleEntryServiceFlow(brief)) {
      try {
        topologyValidator.validate(brief);
      } catch (IllegalArgumentException ex) {
        return Optional.of("invalid single-entry mapping topology: " + ex.getMessage());
      }
    }
    return Optional.empty();
  }

  private static Optional<String> validateProjectedRoles(RequirementBrief brief) {
    RequirementFlow flow = brief.flow();
    if (flow.interactions().isEmpty()) {
      return Optional.empty();
    }
    Set<String> entryIds = new LinkedHashSet<>();
    for (var entryPoint : brief.entryPoints()) {
      if (entryPoint != null && !entryPoint.entryPointId().isBlank()) {
        entryIds.add(entryPoint.entryPointId());
      }
    }
    Set<String> callIds = new LinkedHashSet<>();
    for (RequirementServiceCall call : brief.serviceCalls()) {
      if (call != null && !call.serviceCallId().isBlank()) {
        callIds.add(call.serviceCallId());
      }
    }
    for (Interaction interaction : flow.interactions()) {
      String interactionId = interaction.interactionId();
      if (interaction.direction() == Direction.INBOUND && !entryIds.contains(interactionId)) {
        return Optional.of("requirement brief missing entryPointId=" + interactionId);
      }
      if (interaction.direction() == Direction.OUTBOUND && !callIds.contains(interactionId)) {
        return Optional.of(
            "requirement brief missing serviceCallId="
                + interactionId
                + ", participant="
                + interaction.participant()
                + ", operation="
                + interaction.operation());
      }
    }
    return Optional.empty();
  }

  /**
   * Field adaptation in the approved draft must be captured as mapping intents. Pass-through stays
   * the absence of an intent when the draft did not request field mapping.
   */
  private static Optional<String> validateCapturedMappings(
      RequirementDraft approvedDraft, RequirementBrief brief) {
    if (!describesFieldAdaptation(approvedDraft.planningText())) {
      return Optional.empty();
    }
    if (!brief.mappingIntents().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "The approved draft describes field mappings. Capture them as mappingIntents with"
            + " sourcePath and targetPath on each rule. Leave mappingIntents empty only when"
            + " the payload is pass-through.");
  }

  private static boolean describesFieldAdaptation(String planningText) {
    if (planningText == null || planningText.isBlank()) {
      return false;
    }
    String text = planningText.toLowerCase(Locale.ROOT);
    return text.contains("request mapping")
        || text.contains("response mapping")
        || text.contains("sourcepath")
        || text.contains("targetpath");
  }

  /**
   * Empty brief service-call lists stay a coverage no-op so v1 briefs that only pin facts still
   * pass. A non-empty list must cover every draft {@code serviceCallId}, and every call must carry
   * a catalog binding that names it.
   *
   * <p>Requiring the binding here is what keeps an unbound call from reaching design execution,
   * where nothing upstream can still resolve it and the run can only ask the author again.
   */
  private static Optional<String> validateMappingRoles(RequirementBrief brief) {
    RequirementFlow flow = brief.flow();
    if (flow.interactions().isEmpty()) {
      return Optional.empty();
    }
    Map<String, Interaction> byId = new LinkedHashMap<>();
    for (Interaction interaction : flow.interactions()) {
      byId.put(interaction.interactionId(), interaction);
    }
    for (MappingIntent intent : brief.mappingIntents()) {
      if (intent == null) {
        continue;
      }
      Optional<String> sourceError = validateMappingRef(byId, intent.sourceRef(), intent.sourcePort());
      if (sourceError.isPresent()) {
        return sourceError;
      }
      Optional<String> targetError = validateMappingRef(byId, intent.targetRef(), intent.targetPort());
      if (targetError.isPresent()) {
        return targetError;
      }
      Optional<String> transitionError = validateMappingTransition(flow, intent);
      if (transitionError.isPresent()) {
        return transitionError;
      }
      if (intent.sourcePort() == MappingPort.RESPONSE
          && intent.targetPort() == MappingPort.REQUEST) {
        Interaction source = byId.get(intent.sourceRef());
        Interaction target = byId.get(intent.targetRef());
        if (source.direction() != Direction.OUTBOUND || target.direction() != Direction.OUTBOUND) {
          return Optional.of(
              "RESPONSE -> REQUEST mapping has inverted source or target role");
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<String> validateMappingRef(
      Map<String, Interaction> byId, String ref, MappingPort port) {
    if (ref == null || ref.isBlank()) {
      return Optional.empty();
    }
    Interaction interaction = byId.get(ref);
    if (interaction == null) {
      return Optional.of("mapping reference " + ref + " is not in the requirement flow");
    }
    if (interaction.direction() == Direction.INBOUND && port == MappingPort.REQUEST) {
      return Optional.of(
          "inbound interaction " + ref + " cannot be an outbound REQUEST target");
    }
    if (interaction.direction() == Direction.OUTBOUND && port == MappingPort.OUTPUT) {
      return Optional.of("outbound interaction " + ref + " cannot use OUTPUT");
    }
    return Optional.empty();
  }

  private static Optional<String> validateMappingTransition(
      RequirementFlow flow, MappingIntent intent) {
    String sourceRef = intent.sourceRef();
    String targetRef = intent.targetRef();
    if (sourceRef.isBlank() || targetRef.isBlank()) {
      return Optional.empty();
    }
    for (Transition transition : flow.transitions()) {
      if (sourceRef.equals(transition.sourceInteractionId())
          && targetRef.equals(transition.targetInteractionId())) {
        return Optional.empty();
      }
    }
    return Optional.of(
        "Mapping intent "
            + intent.mappingIntentId()
            + " uses "
            + sourceRef
            + " -> "
            + targetRef
            + ", which is not an approved flow transition. Capture one intent per transition."
            + " Put preserve or echo rules on the hop that writes the target payload.");
  }

  private static Optional<String> validateServiceCalls(RequirementBrief brief) {
    if (brief.serviceCalls().isEmpty()) {
      return Optional.empty();
    }
    Map<String, RequirementServiceCall> briefById = indexCalls(brief.serviceCalls());
    if (briefById.size() != brief.serviceCalls().size()) {
      return Optional.of("requirement brief contains duplicate serviceCallId values");
    }
    for (RequirementServiceCall call : brief.serviceCalls()) {
      if (call.serviceCallId().isBlank()) {
        return Optional.of(
            "requirement brief missing serviceCallId, participant="
                + call.participant()
                + ", operation="
                + call.operation());
      }
      CatalogBindingHint hint = call.catalogBinding();
      if (hint == null) {
        return Optional.of(
            "requirement brief service call has no catalog binding, serviceCallId="
                + call.serviceCallId()
                + ", participant="
                + call.participant()
                + ", operation="
                + call.operation()
                + "; bind it to a catalog operation before approving the brief");
      }
      if (!call.serviceCallId().equals(hint.interactionId())) {
        return Optional.of(
            "requirement brief catalog binding interactionId="
                + hint.interactionId()
                + " does not match call serviceCallId="
                + call.serviceCallId());
      }
    }
    return Optional.empty();
  }

  private static Map<String, RequirementServiceCall> indexCalls(
      List<RequirementServiceCall> calls) {
    Map<String, RequirementServiceCall> byId = new LinkedHashMap<>();
    for (RequirementServiceCall call : calls) {
      if (call == null || call.serviceCallId().isBlank()) {
        continue;
      }
      byId.putIfAbsent(call.serviceCallId(), call);
    }
    return byId;
  }

  private static boolean isSingleEntryServiceFlow(RequirementBrief brief) {
    boolean hasServiceCall =
        !brief.serviceCalls().isEmpty()
            || brief.facts().stream()
                .filter(Objects::nonNull)
                .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
                .anyMatch(fact -> fact.kind() == RequirementFactKind.SERVICE_CALL);
    return brief.entryPoints().size() == 1 && hasServiceCall;
  }

  private static Map<String, RequirementFact> indexById(List<RequirementFact> facts, String label) {
    Map<String, RequirementFact> byId = new LinkedHashMap<>();
    for (RequirementFact fact : facts) {
      RequirementFact previous = byId.putIfAbsent(fact.sourceFactId(), fact);
      if (previous != null) {
        // size check above reports duplicates; keep first
      }
    }
    return byId;
  }
}
