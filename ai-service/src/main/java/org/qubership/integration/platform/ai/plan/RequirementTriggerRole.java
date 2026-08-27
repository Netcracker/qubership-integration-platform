package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/**
 * Resolves chain-entry role from catalog trigger capability keys, not from a free fact kind.
 */
public final class RequirementTriggerRole {

  public static final String MISSING_ENTRY =
      "Requirement brief is missing a configured trigger entry. Capture a trigger before mapping"
          + " validation.";

  private RequirementTriggerRole() {}

  public static boolean isConfiguredTrigger(RequirementFact fact) {
    return fact != null
        && fact.polarity() == RequirementFactPolarity.POSITIVE
        && ChainElementFamilies.isTrigger(fact.capabilityKey());
  }

  public static List<RequirementFact> positiveTriggers(List<RequirementFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return List.of();
    }
    return facts.stream().filter(RequirementTriggerRole::isConfiguredTrigger).toList();
  }

  /**
   * Rewrites an unambiguous catalog-trigger fact whose kind is not {@code ENDPOINT}. Rejects a
   * capture that mixes trigger identity with a service-call kind or a separate non-trigger
   * {@code ENDPOINT}.
   */
  public static List<RequirementFact> canonicalize(List<RequirementFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return facts == null ? List.of() : facts;
    }
    if (hasAmbiguousMixedEntry(facts)) {
      throw new IllegalArgumentException(
          "The capture mixes a catalog trigger capability with a separate ENDPOINT fact that is"
              + " not a catalog trigger. Recapture with one consistent trigger identity.");
    }
    List<RequirementFact> canonical = new ArrayList<>(facts.size());
    for (RequirementFact fact : facts) {
      canonical.add(canonicalizeOne(fact));
    }
    return List.copyOf(canonical);
  }

  private static RequirementFact canonicalizeOne(RequirementFact fact) {
    if (!isConfiguredTrigger(fact)) {
      return fact;
    }
    if (fact.kind() == RequirementFactKind.SERVICE_CALL) {
      throw new IllegalArgumentException(
          "Capability key '"
              + fact.capabilityKey()
              + "' is a catalog trigger, but the fact kind is SERVICE_CALL. Capture the trigger as"
              + " ENDPOINT, or capture a service call with a non-trigger capability key.");
    }
    if (fact.kind() == RequirementFactKind.ENDPOINT) {
      return fact;
    }
    return fact.withKind(RequirementFactKind.ENDPOINT);
  }

  private static boolean hasAmbiguousMixedEntry(List<RequirementFact> facts) {
    boolean hasCatalogTrigger =
        facts.stream().anyMatch(RequirementTriggerRole::isConfiguredTrigger);
    if (!hasCatalogTrigger) {
      return false;
    }
    return facts.stream()
        .filter(Objects::nonNull)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.kind() == RequirementFactKind.ENDPOINT)
        .anyMatch(fact -> !ChainElementFamilies.isTrigger(fact.capabilityKey()));
  }
}
