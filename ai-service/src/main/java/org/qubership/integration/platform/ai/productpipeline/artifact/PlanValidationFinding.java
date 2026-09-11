package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One deterministic or compiler plan-validation finding. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanValidationFinding(
    String code, String message, boolean blocker, MappingValidationDetails mappingDetails) {

  public PlanValidationFinding {
    code = code == null ? "" : code;
    message = message == null ? "" : message;
    mappingDetails = mappingDetails == null ? MappingValidationDetails.empty() : mappingDetails;
  }

  public PlanValidationFinding(String code, String message, boolean blocker) {
    this(code, message, blocker, MappingValidationDetails.empty());
  }
}
