package org.qubership.integration.platform.ai.chat.chainplan;

/**
 * Classifies unresolved plan / materialization items stored on
 * {@link ActiveChainPlanSnapshot}.
 */
public enum PlanOpenItemKind {
  /**
   * Property key present in the plan but not allowed by embedded element JSON
   * Schema.
   */
  UNKNOWN_PROPERTY_KEY,
  /** Catalog PATCH preparation or HTTP update failed for an element. */
  PATCH_VALIDATION,
  /**
   * Model asked the user for clarification; placeholder for future structured
   * capture.
   */
  USER_CLARIFICATION_REQUESTED,
  /**
   * Keys were removed before PATCH so the request could proceed; user should
   * verify in UI.
   */
  SKIPPED_PROPERTIES_PARTIAL,
  /**
   * service-call row lacks integrationOperationId and is not marked
   * user_accepted_unbound.
   */
  SERVICE_BINDING_UNRESOLVED,
  /** Multi-step plan has no runtime {@code connections[]} entries. */
  MISSING_RUNTIME_CONNECTIONS
}
