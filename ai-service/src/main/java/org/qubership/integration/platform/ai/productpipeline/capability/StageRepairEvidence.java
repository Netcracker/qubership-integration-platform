package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;

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
    List<Reference> priorOutputRefs) {

  /**
   * Attribute holding what the halted attempt of the stage now running produced. The runtime
   * rewrites it per execution from that stage's own journal snapshot, so it never carries another
   * stage's outputs and it comes back unchanged after a restart.
   */
  public static final String PRIOR_OUTPUT_REFS_ATTR = "priorAttemptOutputRefs";

  public StageRepairEvidence {
    priorOutputRefs = priorOutputRefs == null ? List.of() : List.copyOf(priorOutputRefs);
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
        priorOutputRefs(context));
  }

  /** True when there is enough evidence to act on: formatted findings or raw error text. */
  public boolean hasEvidence() {
    return (errorEvidence != null && !errorEvidence.isBlank())
        || (findings != null && !findings.isBlank());
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
