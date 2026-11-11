package org.qubership.integration.platform.ai.plan;

/** A company recommendation and the user's response to its persisted question. */
public record RequirementPolicyConflict(
    String policyId,
    String propertyKey,
    String requestedValue,
    String recommendedValue,
    String questionId,
    @NullableCaptureValue(description = "null until the user answers the persisted policy question;")
        Decision decision) {

  public enum Decision {
    KEEP_REQUESTED,
    USE_RECOMMENDED
  }

  public String selectedValue() {
    return decision == Decision.KEEP_REQUESTED ? requestedValue
        : decision == Decision.USE_RECOMMENDED ? recommendedValue : null;
  }

  public RequirementPolicyConflict unresolved() {
    return new RequirementPolicyConflict(policyId, propertyKey, requestedValue, recommendedValue, questionId, null);
  }
}
