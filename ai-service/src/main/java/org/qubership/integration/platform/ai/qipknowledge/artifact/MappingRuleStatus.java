package org.qubership.integration.platform.ai.qipknowledge.artifact;

/**
 * Brief-facing status of one mapping rule. AUTO is identity only; PROPOSED needs confirmation;
 * USER_DEFINED is supplied or edited by the user; UNRESOLVED is missing required coverage.
 */
public enum MappingRuleStatus {
  AUTO,
  PROPOSED,
  USER_DEFINED,
  UNRESOLVED
}
