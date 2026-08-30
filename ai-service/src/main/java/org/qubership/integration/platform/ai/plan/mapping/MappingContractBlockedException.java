package org.qubership.integration.platform.ai.plan.mapping;

/**
 * Mapping generation stopped because required targets are unresolved. Recovery is {@code
 * REVISE_BRIEF}.
 */
public class MappingContractBlockedException extends RuntimeException {

  public MappingContractBlockedException(String message) {
    super(message);
  }
}
