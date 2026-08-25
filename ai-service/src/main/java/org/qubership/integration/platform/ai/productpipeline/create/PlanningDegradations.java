package org.qubership.integration.platform.ai.productpipeline.create;

import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;

/**
 * Findings for the fail-open paths in the compiler DAG. The engine keeps the planning stream alive
 * when a generator fails, which leaves a gap the chain author would otherwise approve without
 * seeing. Each finding is a non-blocker, so {@code PlanValidationResult.approvalEligible()} still
 * lets the plan reach approval; the codes are stable so the card and the transcript can key on them.
 */
public final class PlanningDegradations {

  /** A generator reported a failed status and the run continued without its output. */
  public static final String GENERATOR_SKIPPED = "GENERATOR_SKIPPED";

  /** An artifact captured earlier stood in for a failed generator's output. */
  public static final String FALLBACK_SUBSTITUTED = "FALLBACK_SUBSTITUTED";

  /** Naming produced nothing and the chain carries the soft-default name. */
  public static final String DEFAULT_CHAIN_NAME = "DEFAULT_CHAIN_NAME";

  private PlanningDegradations() {}

  public static PlanValidationFinding generatorSkipped(String skillId) {
    return new PlanValidationFinding(
        GENERATOR_SKIPPED,
        "Generator "
            + skillId
            + " failed, so planning skipped it and continued. Review the plan for what that step"
            + " was meant to add.",
        false);
  }

  public static PlanValidationFinding fallbackSubstituted(String skillId, String artifactType) {
    return new PlanValidationFinding(
        FALLBACK_SUBSTITUTED,
        "Generator "
            + skillId
            + " failed, so planning kept the "
            + artifactType
            + " captured earlier. Check that it still matches what you asked for.",
        false);
  }

  public static PlanValidationFinding defaultChainName(String skillId, String chainName) {
    return new PlanValidationFinding(
        DEFAULT_CHAIN_NAME,
        "Generator "
            + skillId
            + " produced no naming manifest, so the chain is named \""
            + chainName
            + "\". Rename it before the chain is written to the catalog.",
        false);
  }
}
