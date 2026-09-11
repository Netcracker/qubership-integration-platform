package org.qubership.integration.platform.ai.plan.mapping;

/**
 * Stable reason a mapping rule failed contract validation. Distinct from {@code UNRESOLVED}
 * rule status, which several of these reasons still assign.
 */
public enum MappingFindingCode {
  /** Rule targets a path absent from a known target contract. */
  MAPPING_UNKNOWN_TARGET,
  /** Required contract field has no supplying rule. */
  MAPPING_MISSING_REQUIRED_TARGET,
  /** Source violates the selected mechanism's existing source rules. */
  MAPPING_INVALID_SOURCE,
  /** Expression is rejected by the existing mechanism policy. */
  MAPPING_UNSUPPORTED_EXPRESSION,
  /** Captured unresolved rule has no more specific established reason. */
  MAPPING_UNRESOLVED_RULE
}
