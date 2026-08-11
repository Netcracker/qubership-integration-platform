package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * Shared capability ladder for implement intent: durable artifacts decide the next scenario.
 * Never surfaces terminal missing-artifact strings — demotes to the matching product CREATE step.
 */
public final class ImplementCapabilityLadder {

  /** Guidance when demotion lands on requirement discovery (handler cannot open gather itself). */
  public static final String NO_READY_DRAFT_MESSAGE =
      "Describe the integration requirements first so a requirement draft can be captured.";

  /** Guidance when demotion lands on design review (legacy wording; routes to product CREATE). */
  public static final String NO_APPROVED_DESIGN_MESSAGE =
      "Approve the latest requirement draft before creating the chain plan.";

  /** Guidance when demotion lands on planning. */
  public static final String NO_APPROVED_PLAN_MESSAGE =
      "Approve the latest implementation plan before creating the chain plan.";

  /**
   * Legacy terminal strings retained for negative assertions / docs only. Routing no longer emits
   * these; the ladder soft-advances instead.
   */
  public static final String NO_BUNDLE_FOR_IMPLEMENT_MESSAGE =
      "Create a current chain bundle before implementation.";

  public static final String NO_PASSING_PLAN_VALIDATION_MESSAGE =
      "Current passing plan validation is required before implementation. Re-run planning or"
          + " refine the approved artifacts first.";

  /** @deprecated Prefer soft-advance; retained for negative assertions only. */
  @Deprecated(forRemoval = false)
  public static final String NO_READY_REQUIREMENT_DRAFT_MESSAGE =
      "No ready requirement draft available.";

  private ImplementCapabilityLadder() {}

  /**
   * Advance {@link ScenarioType#IMPLEMENT_CHAIN} when derived artifacts are missing.
   *
   * @param hasCurrentBundle current generated chain bundle present
   * @param hasReadyDraft ready-for-plan requirement draft present
   * @param canImplement validation gate allows implement
   */
  public static ScenarioType advance(
      boolean hasCurrentBundle, boolean hasReadyDraft, boolean canImplement) {
    if (!hasCurrentBundle) {
      return hasReadyDraft ? ScenarioType.CREATE_CHAIN_PLAN : ScenarioType.GATHER_REQUIREMENTS;
    }
    if (!canImplement) {
      return ScenarioType.CREATE_CHAIN_PLAN;
    }
    return ScenarioType.IMPLEMENT_CHAIN;
  }

  /** User-facing guidance when a BUILD_CHAIN handler cannot open the demoted scenario. */
  public static String guidanceForDemotion(ScenarioType advanced) {
    if (advanced == ScenarioType.GATHER_REQUIREMENTS) {
      return NO_READY_DRAFT_MESSAGE;
    }
    if (advanced == ScenarioType.CREATE_CHAIN_PLAN) {
      return NO_APPROVED_PLAN_MESSAGE;
    }
    return null;
  }
}
