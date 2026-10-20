package org.qubership.integration.platform.ai.qipknowledge.artifact;

/** How one outbound service-call occurrence delivers a failed invocation. */
public enum ServiceCallFailureMode {
  PROPAGATE,
  INLINE_RESPONSE,
  ERROR_SCOPE
}
