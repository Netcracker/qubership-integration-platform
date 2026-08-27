package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;

/** Outbound catalog or system operation named in the requirement brief. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementServiceCall(
    String serviceCallId,
    String sourceFactId,
    String participant,
    String operation,
    CatalogBindingHint catalogBinding) {

  public RequirementServiceCall {
    serviceCallId = blankToEmpty(serviceCallId);
    sourceFactId = blankToEmpty(sourceFactId);
    participant = blankToEmpty(participant);
    operation = blankToEmpty(operation);
  }

  /** Compatibility constructor used before each call owned a catalog binding. */
  public RequirementServiceCall(
      String serviceCallId, String sourceFactId, String participant, String operation) {
    this(serviceCallId, sourceFactId, participant, operation, null);
  }

  private static String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value.trim();
  }
}
