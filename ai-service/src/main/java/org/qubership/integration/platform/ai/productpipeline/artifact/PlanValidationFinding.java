package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One deterministic or compiler plan-validation finding. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanValidationFinding(
    String code, String message, boolean blocker, MappingValidationDetails mappingDetails,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String originRecordId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String fieldPointer) {

  public PlanValidationFinding {
    code = code == null ? "" : code;
    message = message == null ? "" : message;
    mappingDetails = mappingDetails == null ? MappingValidationDetails.empty() : mappingDetails;
    originRecordId = originRecordId == null ? "" : originRecordId;
    fieldPointer = fieldPointer == null ? "" : fieldPointer;
  }

  public PlanValidationFinding(String code, String message, boolean blocker, MappingValidationDetails mappingDetails) {
    this(code, message, blocker, mappingDetails, "", "");
  }

  public PlanValidationFinding(String code, String message, boolean blocker) {
    this(code, message, blocker, MappingValidationDetails.empty(), "", "");
  }
}
