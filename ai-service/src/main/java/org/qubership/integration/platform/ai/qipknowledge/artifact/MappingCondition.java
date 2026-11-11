package org.qubership.integration.platform.ai.qipknowledge.artifact;

import org.qubership.integration.platform.ai.plan.NullableCaptureValue;

/** Selects an established service outcome before a mapping executes. */
public record MappingCondition(When when, @NullableCaptureValue String interactionId) {
  public enum When { ALWAYS, SUCCESS, HTTP_FAILURE, TRANSPORT_FAILURE, FAILURE }
  public static MappingCondition always() { return new MappingCondition(When.ALWAYS, null); }
}
