package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;

/** Maps a validated recovery decision to a pipeline stage decision. */
public final class RecoveryExecutor {

  private static final List<String> INTERNAL_TERMS =
      List.of(
          "requirement-analysis",
          "design-execution",
          "design-planning",
          "requirement-discovery",
          "__OWNER_CANDIDATES__",
          "go back to");
  private static final String PARK_MESSAGE = "Recovery is parked for review.";
  private static final String CAPTURE_STILL_INVALID =
      "The previous capture was still rejected. What should change in the chain topology?";

  private RecoveryExecutor() {}

  public static StageDecision execute(
      RecoveryDecision decision,
      ProductPipelineRunDocument doc,
      ProfileStage failedStage,
      boolean catalogHasBeenWritten) {
    return execute(decision, null, doc, failedStage, catalogHasBeenWritten, false);
  }

  public static StageDecision execute(
      RecoveryDecision decision,
      RecoveryEvidence evidence,
      ProductPipelineRunDocument doc,
      ProfileStage failedStage,
      boolean catalogHasBeenWritten,
      boolean identicalRejection) {
    String stageId = failedStage == null ? "" : failedStage.stageId();
    if (decision == null) {
      return park(stageId, "");
    }
    RecoveryAction action = decision.action();
    if (catalogHasBeenWritten
        && (action == RecoveryAction.REVISE_BRIEF
            || action == RecoveryAction.REGENERATE_ARTIFACT)) {
      return park(stageId, summary(decision));
    }
    if (action == null) {
      return park(stageId, summary(decision));
    }
    if (identicalRejection && action == RecoveryAction.REGENERATE_ARTIFACT) {
      return askUser(stageId, summary(decision));
    }
    return switch (action) {
      case REVISE_BRIEF ->
          new StageDecision.ReopenProducer(
              stageId, briefRepairOwner(decision, evidence), summary(decision));
      case REGENERATE_ARTIFACT -> regenerate(stageId, decision, failedStage);
      case RETRY_OPERATION -> retry(stageId, failedStage);
      case ASK_USER ->
          askUser(stageId, join(summary(decision), sanitize(decision.question())));
      case PARK -> park(stageId, summary(decision));
    };
  }

  private static StageDecision regenerate(
      String stageId, RecoveryDecision decision, ProfileStage failedStage) {
    String failedStageId = failedStage == null ? stageId : failedStage.stageId();
    String producerStageId = producerStageForFault(decision.faultArtifactRef(), failedStageId);
    if (producerStageId.equals(failedStageId) || producerStageId.isBlank()) {
      return new StageDecision.Retry(stageId, Duration.ZERO);
    }
    return new StageDecision.ReopenProducer(stageId, producerStageId);
  }

  private static StageDecision.Retry retry(String stageId, ProfileStage failedStage) {
    long delayMs =
        failedStage == null || failedStage.retry() == null
            ? 0L
            : failedStage.retry().defaultDelayMs();
    return new StageDecision.Retry(stageId, Duration.ofMillis(Math.max(delayMs, 0L)));
  }

  /**
   * Owner of a brief defect. The brief projects entry point and service call fields from the
   * approved draft, so when the producer reported missing brief facts the defect belongs to the
   * stage that wrote the draft: rebuilding the brief from the same draft reproduces it. Every other
   * brief defect keeps the brief itself as the owner.
   */
  static String briefRepairOwner(RecoveryDecision decision, RecoveryEvidence evidence) {
    if (evidence != null
        && RecoveryCauseCode.MISSING_BRIEF_FACTS.name().equals(evidence.observedCauseCode())) {
      return "requirement-discovery";
    }
    String producer =
        producerStageForFault(
            decision == null ? null : decision.faultArtifactRef(), "requirement-analysis");
    return "requirement-discovery".equals(producer) ? producer : "requirement-analysis";
  }

  static String producerStageForFault(Reference fault, String failedStageId) {
    if (fault == null || fault.kind() == null) {
      return failedStageId;
    }
    return switch (fault.kind()) {
      case REQUIREMENT_DRAFT -> "requirement-discovery";
      case REQUIREMENT_BRIEF -> "requirement-analysis";
      case IDS_DOCUMENT, CHAIN_SEMANTIC_REVISION -> "design-input";
      case IMPLEMENTATION_PLAN, DESIGN_PLAN_REPORT, DESIGN_EXECUTION_PLAN -> "design-planning";
      case CHAIN_PLAN_GRAPH,
              GRAPH_PATCH_ARTIFACT,
              GRAPH_ASSEMBLY_RESULT,
              ORDERED_GRAPH_PATCHES,
              DESIGN_EXECUTION_CHECKPOINT,
              DESIGN_EXECUTION_RESULT,
              EXECUTION_TRACE,
              API_OPERATION_BINDINGS,
              PLAN_VALIDATION_RESULT,
              COMPILER_VALIDATION_BUNDLE,
              EXECUTOR_VALIDATION_BUNDLE,
              VALIDATED_EXECUTION_BUNDLE,
              MATERIALIZATION_REQUEST ->
          "design-execution";
      default -> failedStageId;
    };
  }

  private static StageDecision.WaitForInput park(String stageId, String summary) {
    String body = summary.isBlank() ? PARK_MESSAGE : summary;
    return new StageDecision.WaitForInput(
        stageId, PipelineGates.tag(PipelineGates.STAGE_RETRY, body));
  }

  private static StageDecision.WaitForInput askUser(String stageId, String body) {
    String question = body == null || body.isBlank() ? CAPTURE_STILL_INVALID : body;
    return new StageDecision.WaitForInput(
        stageId, PipelineGates.tag(PipelineGates.STAGE_CLARIFICATION, question));
  }

  private static String summary(RecoveryDecision decision) {
    return decision == null ? "" : sanitize(decision.userSummary());
  }

  private static String join(String summary, String question) {
    if (summary.isBlank()) {
      return question;
    }
    if (question.isBlank() || question.equals(summary)) {
      return summary;
    }
    if (question.startsWith(summary) || summary.startsWith(question)) {
      return question.length() >= summary.length() ? question : summary;
    }
    return summary + "\n\n" + question;
  }

  private static String sanitize(String text) {
    String sanitized = text == null ? "" : text;
    for (String term : INTERNAL_TERMS) {
      int index;
      while ((index = sanitized.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT)))
          >= 0) {
        sanitized = sanitized.substring(0, index) + sanitized.substring(index + term.length());
      }
    }
    return sanitized.trim();
  }
}
