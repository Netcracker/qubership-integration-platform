package org.qubership.integration.platform.ai.productpipeline.stage;

import java.time.Duration;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Terminal decision of one stage-module invocation. Flow applies lifecycle transitions. The stage
 * module never selects the next stage, sleeps, or recurses.
 */
public sealed interface StageDecision {

  String stageId();

  record Continue(String stageId) implements StageDecision {}

  record WaitForInput(String stageId, String prompt) implements StageDecision {

    public WaitForInput {
      prompt = prompt == null ? "" : prompt;
    }
  }

  record WaitForApproval(
      String stageId, CompilationArtifacts.Reference candidate, String prompt)
      implements StageDecision {

    public WaitForApproval {
      prompt = prompt == null ? "" : prompt;
    }
  }

  record WaitForImplementation(String stageId, String approvedPlanContentHash)
      implements StageDecision {

    public WaitForImplementation {
      approvedPlanContentHash = approvedPlanContentHash == null ? "" : approvedPlanContentHash;
    }
  }

  record Retry(String stageId, Duration delay) implements StageDecision {

    public Retry {
      delay = delay == null ? Duration.ZERO : delay;
    }
  }

  record ReopenApproval(
      String stageId,
      String approvalStageId,
      String message,
      List<CompilationArtifacts.Reference> evidenceRefs)
      implements StageDecision {

    public ReopenApproval {
      message = message == null ? "" : message;
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
  }

  record Fail(String stageId, StageOutcomeClass outcomeClass, String message)
      implements StageDecision {}

  record Complete(String stageId, RunStatus status) implements StageDecision {}
}
