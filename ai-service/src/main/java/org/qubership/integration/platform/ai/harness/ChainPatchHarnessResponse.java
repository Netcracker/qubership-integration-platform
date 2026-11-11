package org.qubership.integration.platform.ai.harness;

import java.util.List;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriteResult;

/**
 * Response body for {@code POST /api/v1/harness/chain-patch-run}.
 *
 * <p>{@code changedElementIds}, {@code failedElementIds} and {@code removedElementIds} are catalog
 * element ids, so the caller can fetch each one back from the catalog -- or confirm it is gone --
 * without knowing how the patch pipeline named it internally. {@code refusal} names which gate
 * turned the patch away, so a report can tell a permissions problem from a malformed patch from one
 * that would break the chain. {@code rollback} says what became of a write that failed partway, so
 * a suite knows whether the chain it is about to assert on was put back or left as it fell.
 */
public record ChainPatchHarnessResponse(
    String conversationId,
    SkillHarnessStatus status,
    String message,
    ChainPatchRefusal refusal,
    List<String> changedElementIds,
    List<String> failedElementIds,
    List<String> removedElementIds,
    ChainPatchWriteResult.RollbackOutcome rollback) {

  /** For a run that removed nothing. */
  public ChainPatchHarnessResponse(
      String conversationId,
      SkillHarnessStatus status,
      String message,
      ChainPatchRefusal refusal,
      List<String> changedElementIds,
      List<String> failedElementIds) {
    this(conversationId, status, message, refusal, changedElementIds, failedElementIds, List.of(), null);
  }

  /** For a run that had nothing to unwind. */
  public ChainPatchHarnessResponse(
      String conversationId,
      SkillHarnessStatus status,
      String message,
      ChainPatchRefusal refusal,
      List<String> changedElementIds,
      List<String> failedElementIds,
      List<String> removedElementIds) {
    this(
        conversationId,
        status,
        message,
        refusal,
        changedElementIds,
        failedElementIds,
        removedElementIds,
        null);
  }

  public ChainPatchHarnessResponse {
    refusal = refusal == null ? ChainPatchRefusal.NONE : refusal;
    changedElementIds = changedElementIds == null ? List.of() : List.copyOf(changedElementIds);
    failedElementIds = failedElementIds == null ? List.of() : List.copyOf(failedElementIds);
    removedElementIds = removedElementIds == null ? List.of() : List.copyOf(removedElementIds);
    rollback = rollback == null ? ChainPatchWriteResult.RollbackOutcome.NOT_ATTEMPTED : rollback;
  }

  /**
   * Whether the patch was turned away for reaching outside what the skill owns.
   *
   * <p>Kept so a report that only cares about the scope guarantee -- the regression suite's whole
   * reason for existing -- does not have to know the rest of the enum.
   */
  public boolean scopeViolation() {
    return refusal == ChainPatchRefusal.OWNERSHIP;
  }
}
