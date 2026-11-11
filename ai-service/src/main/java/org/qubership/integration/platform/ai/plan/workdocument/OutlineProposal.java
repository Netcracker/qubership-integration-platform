package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/**
 * Typed outline capture for one target step. Indexing and coverage calculation stay with a later
 * ticket; this record is the editor input.
 */
public record OutlineProposal(
    String targetStepId,
    List<OutlineTransfer> transfers,
    List<OutlineRetained> retainedPlaceholders,
    List<OutlineCoverage> coverage) {

  public OutlineProposal {
    transfers = Lists.copy(transfers);
    retainedPlaceholders = Lists.copy(retainedPlaceholders);
    coverage = Lists.copy(coverage);
    targetStepId = targetStepId == null ? "" : targetStepId;
  }
}

record OutlineTransfer(
    String alias,
    String existingId,
    List<PortRef> sourcePorts,
    PortRef targetPort,
    TransferOutcome outcome,
    List<String> requirementIds,
    List<String> requiredRetainedIds,
    String decision) {

  public OutlineTransfer {
    sourcePorts = Lists.copy(sourcePorts);
    requirementIds = Lists.copy(requirementIds);
    requiredRetainedIds = Lists.copy(requiredRetainedIds);
    alias = alias == null ? "" : alias;
    existingId = existingId == null ? "" : existingId;
    outcome = outcome == null ? TransferOutcome.UNSPECIFIED : outcome;
    decision = decision == null ? "" : decision;
  }
}

record OutlineRetained(
    String alias,
    String existingId,
    String producerStepId,
    String intendedUse,
    List<String> evidenceIds) {

  public OutlineRetained {
    evidenceIds = Lists.copy(evidenceIds);
    alias = alias == null ? "" : alias;
    existingId = existingId == null ? "" : existingId;
    producerStepId = producerStepId == null ? "" : producerStepId;
    intendedUse = intendedUse == null ? "" : intendedUse;
  }
}

record OutlineCoverage(String requirementId, String passageId, CoverageDisposition disposition) {

  public OutlineCoverage {
    requirementId = requirementId == null ? "" : requirementId;
    passageId = passageId == null ? "" : passageId;
    disposition = disposition == null ? CoverageDisposition.ASSIGNED : disposition;
  }
}
