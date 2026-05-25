package org.qubership.integration.platform.ai.chat.chainplan;

/** Result of publishing a {@link ChainImplementationPlan} into conversation state. */
public record PlanPublicationOutcome(
    boolean captured,
    String planId,
    String chainName,
    int elementCount,
    int openItemCount,
    String failureCode,
    String failureMessage) {

  public static PlanPublicationOutcome success(
      String planId, String chainName, int elementCount, int openItemCount) {
    return new PlanPublicationOutcome(
        true, planId, chainName, elementCount, openItemCount, null, null);
  }

  public static PlanPublicationOutcome failure(String code, String message) {
    return new PlanPublicationOutcome(false, null, null, 0, 0, code, message);
  }
}
