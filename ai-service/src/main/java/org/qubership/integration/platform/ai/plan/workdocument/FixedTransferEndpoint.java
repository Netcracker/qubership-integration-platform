package org.qubership.integration.platform.ai.plan.workdocument;

/** Transfer identity, target port, and outcome that a mapping task cannot change. */
public record FixedTransferEndpoint(String transferId, PortRef targetPort, TransferOutcome outcome) {

  public FixedTransferEndpoint {
    outcome = outcome == null ? TransferOutcome.UNSPECIFIED : outcome;
  }

  /** Builds an endpoint without exposing the package-private port type to mapping callers. */
  public static FixedTransferEndpoint of(
      String transferId, String stepId, String portName, TransferOutcome outcome) {
    return new FixedTransferEndpoint(transferId, new PortRef(stepId, portName), outcome);
  }
}
