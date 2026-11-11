package org.qubership.integration.platform.ai.plan.mapping;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;

/**
 * Mapping generation stopped because the contract rejected one or more rules. Recovery belongs
 * to the producer of those rule bodies.
 */
public class MappingContractBlockedException extends RuntimeException {

  private final List<PlanValidationFinding> findings;

  public MappingContractBlockedException(String message) {
    this(message, List.of());
  }

  public MappingContractBlockedException(String message, List<PlanValidationFinding> findings) {
    super(message);
    this.findings = findings == null ? List.of() : List.copyOf(findings);
  }

  public List<PlanValidationFinding> findings() {
    return findings;
  }
}
