package org.qubership.integration.platform.ai.plan.workdocument;

/** Transfer identity, target port, and outcome that a mapping task cannot change. */
public record FixedTransferEndpoint(String transferId, PortRef targetPort, TransferOutcome outcome) {

  public FixedTransferEndpoint {
    outcome = outcome == null ? TransferOutcome.UNSPECIFIED : outcome;
  }
}
