package org.qubership.integration.platform.ai.productpipeline.runtime;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/** Durable wait, failure, and completion signals emitted by the product-pipeline runtime. */
public sealed interface PipelineSignal {

  record Message(String text) implements PipelineSignal {}

  record Progress(String stageId, String label) implements PipelineSignal {}

  /** Maps to chat {@code event: step} with {@code kind=skill}. */
  record SkillProgress(String skillId, String status) implements PipelineSignal {}

  /**
   * @param prompt user-facing next-step text from the stage outcome; may be blank when the wait is
   *     re-emitted without a fresh NEEDS_INPUT message
   */
  record WaitingForInput(String stageId, String prompt) implements PipelineSignal {

    public WaitingForInput {
      prompt = prompt == null ? "" : prompt;
    }
  }

  /**
   * @param prompt user-facing Agree CTA in the conversation language; may be blank on resume when
   *     the stage already streamed an approval ask
   */
  record WaitingForApproval(
      String stageId, CompilationArtifacts.Reference candidate, String prompt)
      implements PipelineSignal {

    public WaitingForApproval {
      prompt = prompt == null ? "" : prompt;
    }

    /** Compatibility for callers that only have stage + candidate. */
    public WaitingForApproval(String stageId, CompilationArtifacts.Reference candidate) {
      this(stageId, candidate, "");
    }
  }

  /**
   * @param approvedPlanContentHash SHA-256 of the approved implementation plan; may be blank when
   *     the hash cannot be resolved yet
   */
  record WaitingForImplement(String stageId, String approvedPlanContentHash)
      implements PipelineSignal {

    public WaitingForImplement {
      approvedPlanContentHash = approvedPlanContentHash == null ? "" : approvedPlanContentHash;
    }
  }

  record Failed(String stageId, StageOutcomeClass outcomeClass, String message)
      implements PipelineSignal {}

  record Completed(RunStatus status) implements PipelineSignal {}
}
