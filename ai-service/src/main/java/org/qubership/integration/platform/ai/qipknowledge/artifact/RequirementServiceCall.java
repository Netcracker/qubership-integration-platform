package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Outbound catalog or system operation named in the requirement brief. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequirementServiceCall(
    String serviceCallId, String sourceFactId, String participant, String operation) {

  public RequirementServiceCall {
    serviceCallId = blankToEmpty(serviceCallId);
    sourceFactId = blankToEmpty(sourceFactId);
    participant = blankToEmpty(participant);
    operation = blankToEmpty(operation);
  }

  private static String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value.trim();
  }
}
