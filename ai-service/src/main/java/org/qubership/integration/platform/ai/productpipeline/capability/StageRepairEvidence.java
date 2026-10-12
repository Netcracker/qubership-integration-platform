package org.qubership.integration.platform.ai.productpipeline.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.capture.ToolArgumentsFailures;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContextProjector;

/**
 * Halt evidence available to a capability re-entering after a recoverable halt: the outcome class,
 * the stage that produced the failure, formatted validation findings, the raw error text, the
 * author's typed follow-up left at the pause, and the artifacts the halted attempt of this same
 * stage produced. The runtime writes all of it into run attributes before it halts (see {@code
 * ProductPipelineStageExecutor.haltRecoverable}); this is the one place that reads them back, so a
 * stage capability does not keep a private copy of the same lookup.
 *
 * <p>{@link #priorOutputRefs()} names artifacts a halted attempt wrote, never approved work. They
 * reach a capability only through this value and only on a repair turn, so nothing downstream can
 * consume them and nothing treats them as a product of the pipeline.
 */
public record StageRepairEvidence(
    String outcomeClass,
    String failedStageId,
    String findings,
    String errorEvidence,
    String haltFollowUpText,
    String recoveryEvidenceRef,
    List<Reference> priorOutputRefs,
    String failedSkillId,
    String causeCode,
    String typedCauseJson,
    String rejectedCandidateJson,
    String repairHistoryJson) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Attribute holding what the halted attempt of the stage now running produced. The runtime
   * rewrites it per execution from that stage's own journal snapshot, so it never carries another
   * stage's outputs and it comes back unchanged after a restart.
   */
  public static final String REPAIR_HISTORY_ATTR = "repairHistory";

  public static final String PRIOR_OUTPUT_REFS_ATTR = "priorAttemptOutputRefs";

  public StageRepairEvidence {
    repairHistoryJson = repairHistoryJson == null ? "" : repairHistoryJson;
    priorOutputRefs = priorOutputRefs == null ? List.of() : List.copyOf(priorOutputRefs);
    failedSkillId = failedSkillId == null ? "" : failedSkillId;
    causeCode = causeCode == null ? "" : causeCode;
    typedCauseJson = typedCauseJson == null ? "" : typedCauseJson;
    rejectedCandidateJson = rejectedCandidateJson == null ? "" : rejectedCandidateJson;
  }

  public StageRepairEvidence(String outcomeClass, String failedStageId, String findings,
      String errorEvidence, String haltFollowUpText, String recoveryEvidenceRef,
      List<Reference> priorOutputRefs, String failedSkillId, String causeCode,
      String typedCauseJson, String rejectedCandidateJson) {
    this(outcomeClass, failedStageId, findings, errorEvidence, haltFollowUpText, recoveryEvidenceRef,
        priorOutputRefs, failedSkillId, causeCode, typedCauseJson, rejectedCandidateJson, "");
  }

  public StageRepairEvidence(
      String outcomeClass, String failedStageId, String findings, String errorEvidence,
      String haltFollowUpText, String recoveryEvidenceRef, List<Reference> priorOutputRefs,
      String failedSkillId, String causeCode) {
    this(outcomeClass, failedStageId, findings, errorEvidence, haltFollowUpText,
        recoveryEvidenceRef, priorOutputRefs, failedSkillId, causeCode, "", "");
  }

  public StageRepairEvidence(
      String outcomeClass, String failedStageId, String findings, String errorEvidence,
      String haltFollowUpText, String recoveryEvidenceRef, List<Reference> priorOutputRefs) {
    this(outcomeClass, failedStageId, findings, errorEvidence, haltFollowUpText,
        recoveryEvidenceRef, priorOutputRefs, "", "");
  }

  /** Halt evidence with no artifact behind it: the attempt failed before producing one. */
  public StageRepairEvidence(
      String outcomeClass,
      String failedStageId,
      String findings,
      String errorEvidence,
      String haltFollowUpText) {
    this(
        outcomeClass,
        failedStageId,
        findings,
        errorEvidence,
        haltFollowUpText,
        "",
        List.of());
  }

  /** Halt evidence without prior attempt outputs or a recovery evidence ref. */
  public StageRepairEvidence(
      String outcomeClass,
      String failedStageId,
      String findings,
      String errorEvidence,
      String haltFollowUpText,
      List<Reference> priorOutputRefs) {
    this(
        outcomeClass,
        failedStageId,
        findings,
        errorEvidence,
        haltFollowUpText,
        "",
        priorOutputRefs);
  }

  /**
   * True once the runtime has recorded a halt for this execution, meaning the current attempt is a
   * repair turn rather than a first turn. Checked separately from {@link #from} so a caller that
   * only needs the predicate is not forced to build the full value.
   */
  public static boolean isRepairTurn(StageExecutionContext context) {
    return context != null && haltRecorded(context.attributes());
  }

  /**
   * The same rule against a raw attribute map, for the runtime, which decides what a repair turn
   * may read before a {@link StageExecutionContext} exists.
   */
  public static boolean haltRecorded(Map<String, Object> attributes) {
    Object error =
        attributes == null
            ? null
            : attributes.get(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR);
    return error instanceof String text && !text.isBlank();
  }

  /** Reads the halt attributes off {@code context}; {@code null} on a first turn. */
  public static StageRepairEvidence from(StageExecutionContext context) {
    if (!isRepairTurn(context)) {
      return null;
    }
    return new StageRepairEvidence(
        context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR),
        context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR),
        context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR),
        context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR),
        context.attributeAsString(ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR),
        context.attributeAsString(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR),
        priorOutputRefs(context),
        context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_FAILED_SKILL_ATTR),
        context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR),
        context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_JSON_ATTR),
        "", context.attributeAsString(REPAIR_HISTORY_ATTR));
  }

  /** Attaches an exact, lineage-checked candidate without putting it in the approved compiler seed. */
  public StageRepairEvidence withRejectedCandidate(ChainPlanGraph candidate) {
    return withRepairInput(candidate, null);
  }

  public StageRepairEvidence withRepairInput(ChainPlanGraph candidate, Reference selectedGraph) {
    List<Reference> selectedRefs = priorOutputRefs;
    if (selectedGraph != null) {
      var refs = new java.util.ArrayList<>(priorOutputRefs.stream()
          .filter(ref -> ref.kind() != Kind.CHAIN_PLAN_GRAPH).toList());
      refs.add(selectedGraph);
      selectedRefs = List.copyOf(refs);
    }
    try {
      return new StageRepairEvidence(outcomeClass, failedStageId, findings, errorEvidence,
          haltFollowUpText, recoveryEvidenceRef, selectedRefs, failedSkillId, causeCode,
          typedCauseJson, candidate == null ? "" : JSON.writeValueAsString(candidate), repairHistoryJson);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot project the rejected repair candidate", e);
    }
  }

  /** Diagnostic fields for a model turn. Complete raw error text stays in the halt evidence. */
  public String diagnosticText() {
    StringBuilder text = new StringBuilder();
    if (outcomeClass != null && !outcomeClass.isBlank()) {
      text.append("- outcomeClass: ").append(outcomeClass).append('\n');
    }
    if (failedStageId != null && !failedStageId.isBlank()) {
      text.append("- failedStageId: ").append(failedStageId).append('\n');
    }
    if (!causeCode.isBlank()) {
      text.append("- causeCode: ").append(causeCode).append('\n');
    }
    if (findings != null && !findings.isBlank()) {
      text.append("- validationFindings:\n").append(findings).append('\n');
    } else if ("CONTRACT_SHAPE".equals(causeCode)) {
      text.append("- validationFindings: ").append(ToolArgumentsFailures.DIAGNOSTIC).append('\n');
    }
    if (recoveryEvidenceRef != null && !recoveryEvidenceRef.isBlank()) {
      text.append("- recoveryEvidenceRef: ").append(recoveryEvidenceRef).append('\n');
    }
    if (!typedCauseJson.isBlank()) {
      text.append("- typedCause: ").append(typedCauseJson).append('\n');
    }
    if (!rejectedCandidateJson.isBlank()) {
      text.append("- rejectedCandidate (unapproved repair context): ")
          .append(rejectedCandidateJson).append('\n');
    }
    text.append(rejectedCandidateJson.isBlank()
        ? "Raw rejected output is retained in the halt evidence and is excluded from this request.\n"
        : "Raw transport output stays in the halt evidence and is excluded from this request.\n");
    text.append(org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.block(repairHistoryJson));
    return bounded(text.toString());
  }

  /** An attributed failure reaches its owner; unscoped final validation reaches the approved stage producers. */
  public String repairTextForSkill(String skillId) {
    if (!"design-execution".equals(failedStageId) || !hasEvidence()
        || (!failedSkillId.isBlank() && !failedSkillId.equals(skillId))) {
      return "";
    }
    StringBuilder text = new StringBuilder("\n\nRepair evidence for ")
        .append(skillId).append(":\n").append(diagnosticText());
    if (haltFollowUpText != null && !haltFollowUpText.isBlank()) {
      text.append("Author follow-up:\n").append(haltFollowUpText).append('\n');
    }
    if (failedSkillId.isBlank()) {
      text.append("The final validator did not attribute this failure to an individual skill.\n");
    }
    return bounded(text.append("Correct only output you own while preserving the approved requirements and other producers' output.\n")
        .toString());
  }

  private static String bounded(String text) {
    if (text.length() > RecoveryContextProjector.CHAR_BUDGET) {
      throw new IllegalArgumentException("Producer repair context exceeds the diagnostic character budget");
    }
    return text;
  }

  /** True when there is enough evidence to act on: formatted findings or raw error text. */
  public boolean hasEvidence() {
    return (errorEvidence != null && !errorEvidence.isBlank())
        || (findings != null && !findings.isBlank()) || !typedCauseJson.isBlank();
  }

  /** True only for the recovery evidence that received the one-shot clean rebuild budget. */
  public static boolean isCleanDesignPlanRebuild(
      StageExecutionContext context, StageRepairEvidence evidence) {
    return isCleanRebuild(
        context,
        evidence,
        ProductPipelineRunSupport.DESIGN_PLAN_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
  }

  /** True only for the recovery evidence that received a clean design-input rebuild. */
  public static boolean isCleanDesignInputRebuild(
      StageExecutionContext context, StageRepairEvidence evidence) {
    return isCleanRebuild(
        context,
        evidence,
        ProductPipelineRunSupport.DESIGN_INPUT_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
  }

  public static boolean isCleanExecutionRebuild(
      StageExecutionContext context, StageRepairEvidence evidence) {
    return isCleanRebuild(context, evidence,
        ProductPipelineRunSupport.PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR);
  }

  private static boolean isCleanRebuild(
      StageExecutionContext context, StageRepairEvidence evidence, String selectedEvidenceAttr) {
    if (context == null || evidence == null || evidence.recoveryEvidenceRef() == null) {
      return false;
    }
    String selected = context.attributeAsString(selectedEvidenceAttr);
    return evidence.recoveryEvidenceRef().equals(selected);
  }

  /**
   * The last output of one kind the halted attempt recorded, or empty when the attempt never got
   * that far. The caller resolves the payload through the artifact store it already holds.
   */
  public Optional<Reference> priorOutput(Kind kind) {
    if (kind == null) {
      return Optional.empty();
    }
    return priorOutputRefs.stream()
        .filter(ref -> ref != null && ref.kind() == kind)
        .reduce((first, second) -> second);
  }

  public static boolean matchesCompileInputs(Revision graph, List<Reference> inputRefs) {
    return graph != null && inputRefs != null && inputRefs.stream()
        .filter(ref -> switch (ref.kind()) {
          case CHAIN_SEMANTIC_REVISION, DESIGN_EXECUTION_PLAN, IMPLEMENTATION_PLAN -> true;
          default -> false;
        }).allMatch(graph.inputs()::contains);
  }

  private static List<Reference> priorOutputRefs(StageExecutionContext context) {
    if (!(context.attributes().get(PRIOR_OUTPUT_REFS_ATTR) instanceof List<?> values)) {
      return List.of();
    }
    List<Reference> refs = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof Reference reference) {
        refs.add(reference);
      }
    }
    return List.copyOf(refs);
  }
}
