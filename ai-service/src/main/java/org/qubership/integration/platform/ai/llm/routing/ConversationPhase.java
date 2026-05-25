package org.qubership.integration.platform.ai.llm.routing;

/**
 * Derived conversation phase for routing (in-memory, from {@link
 * org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService}).
 */
public enum ConversationPhase {
  /** No captured chain implementation plan. */
  COLD,
  /** Plan exists, not approved, with unresolved service-binding open items. */
  DISCOVERY,
  /** Plan exists, not approved (draft / revise). */
  PLAN_DRAFT,
  /** Plan exists and approved for execution. */
  PLAN_APPROVED
}
