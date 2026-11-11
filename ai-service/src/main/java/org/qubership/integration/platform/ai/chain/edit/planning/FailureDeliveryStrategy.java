package org.qubership.integration.platform.ai.chain.edit.planning;

/** How an error-handling wrap delivers a failure from the named service-call. */
public enum FailureDeliveryStrategy {
  HTTP_RESPONSE,
  OM_TASK_RESULT,
  EXPLICIT_HANDLER,
  CLARIFY
}
