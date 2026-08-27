package org.qubership.integration.platform.ai.productpipeline.capability;

/**
 * Typed reason a create-chain producer halted. Produced at the failure site and read by recovery
 * routing; never recovered from formatted prose or exception messages.
 */
public enum RecoveryCauseCode {
  /** {@code cip-security-validator} (or equivalent) rejected the graph on policy. */
  SECURITY_POLICY,

  /** A generator submitted a property the element schema does not declare. */
  UNKNOWN_PROPERTY,

  /** A plan or execution step omitted a required setting the schema names. */
  MISSING_REQUIRED_PROPERTY,

  /** Design-input could not derive a flow because the approved brief lacks facts. */
  MISSING_BRIEF_FACTS,

  /** Catalog binding did not resolve to one service or operation. */
  CATALOG_RESOLUTION,

  /** The stage contract rejected a model or adapter payload. */
  CONTRACT_SHAPE,

  /** A blocker finding that names no more specific cause. */
  VALIDATION_BLOCKER,

  /** A required input artifact was absent. */
  MISSING_MANDATORY_INPUT,

  /** A policy other than security-validator rejected the turn. */
  POLICY_FAILURE,

  /** A domain check failed that is not catalog resolution. */
  DOMAIN_FAILURE,

  /** Technical retries on this stage are spent. */
  TECHNICAL_RETRY_EXHAUSTED,

  /** An invariant inside the service broke. */
  INTERNAL
}
