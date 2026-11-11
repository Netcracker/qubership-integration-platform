package org.qubership.integration.platform.ai.plan;

import java.util.List;

/** Claims bound to one source generation and the author-evidence hash of that packet. */
public record RequirementSemanticBaseline(
    String sourceGeneration,
    String authorEvidenceHash,
    List<RequirementSemanticClaim> claims) {

  public RequirementSemanticBaseline {
    claims = claims == null ? List.of() : List.copyOf(claims);
  }
}
