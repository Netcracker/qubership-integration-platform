package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignRequirementBriefCoverageValidator;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

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
    // Catalog import (withCatalogBinding) may promote a draft to READY_FOR_PLAN before gather
    // distilled explicit facts — typically when the turn only captured an APIHub candidate.
    // Coverage is then a no-op: there are no sourceFactId values to pin or mismatch.
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
    if (isSingleEntryServiceFlow(brief)) {
      try {
        topologyValidator.validate(brief);
      } catch (IllegalArgumentException ex) {
        return Optional.of("invalid single-entry mapping topology: " + ex.getMessage());
      }
    }
    return Optional.empty();
  }

  private static boolean isSingleEntryServiceFlow(RequirementBrief brief) {
    long entries = RequirementTriggerRole.positiveTriggers(brief.facts()).size();
    boolean hasServiceCall =
        brief.facts().stream()
            .filter(Objects::nonNull)
            .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
            .anyMatch(fact -> fact.kind() == RequirementFactKind.SERVICE_CALL);
    return entries == 1 && hasServiceCall;
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
