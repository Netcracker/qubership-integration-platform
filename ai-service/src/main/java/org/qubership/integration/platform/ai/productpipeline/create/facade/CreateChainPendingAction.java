package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;

/** Structured pending action advertised on an input-required snapshot. */
public sealed interface CreateChainPendingAction extends PendingAction {

  @Override
  String action();

  /** Exact approval of the current expected artifact. */
  record Approve(
      String artifactType, String artifactHash, long revision, String prompt)
      implements CreateChainPendingAction, PendingAction.Approve {

    public Approve {
      Objects.requireNonNull(artifactType, "artifactType");
      Objects.requireNonNull(artifactHash, "artifactHash");
      prompt = prompt == null ? "" : prompt;
    }

    @Override
    public String action() {
      return "approve";
    }
  }

  /** Clarification when required evidence is missing and recovery can accept input. */
  record Clarify(
      String reason,
      List<String> missingEvidence,
      String gateId,
      String technicalDetails,
      Long retryDelayMs,
      String runId,
      String failedStageId)
      implements CreateChainPendingAction, PendingAction.Clarify {

    public Clarify(String reason, List<String> missingEvidence) {
      this(reason, missingEvidence, "", "", null, "", "");
    }

    public Clarify(String reason, List<String> missingEvidence, String gateId) {
      this(reason, missingEvidence, gateId, "", null, "", "");
    }

    public Clarify {
      Objects.requireNonNull(reason, "reason");
      missingEvidence =
          missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
      gateId = gateId == null ? "" : gateId;
      technicalDetails = technicalDetails == null ? "" : technicalDetails;
      runId = runId == null ? "" : runId;
      failedStageId = failedStageId == null ? "" : failedStageId;
    }

    @Override
    public String action() {
      return "clarify";
    }
  }
}
