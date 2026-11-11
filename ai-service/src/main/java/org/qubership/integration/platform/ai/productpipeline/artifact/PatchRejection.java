package org.qubership.integration.platform.ai.productpipeline.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Rejected graph patch with findings that left the input graph revision unchanged. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PatchRejection(
    String capabilityId, String patchId, String inputGraphDigest, List<String> findings) {

  public PatchRejection {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }
}
