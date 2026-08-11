package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.List;
import java.util.Objects;

/** Structured pending action advertised on an input-required snapshot. */
public sealed interface CreateChainPendingAction {

  String action();

  /** Exact approval of the current expected artifact. */
  record Approve(
      String artifactType, String artifactHash, long revision, String prompt)
      implements CreateChainPendingAction {

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
  record Clarify(String reason, List<String> missingEvidence)
      implements CreateChainPendingAction {

    public Clarify {
      Objects.requireNonNull(reason, "reason");
      missingEvidence =
          missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
    }

    @Override
    public String action() {
      return "clarify";
    }
  }
}
