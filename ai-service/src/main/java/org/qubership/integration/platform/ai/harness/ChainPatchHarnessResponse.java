package org.qubership.integration.platform.ai.harness;

import java.util.List;

/**
 * Response body for {@code POST /api/v1/harness/chain-patch-run}.
 *
 * <p>{@code changedElementIds}, {@code failedElementIds} and {@code removedElementIds} are catalog
 * element ids, so the caller can fetch each one back from the catalog -- or confirm it is gone --
 * without knowing how the patch pipeline named it internally. {@code refusal} names which gate
 * turned the patch away, so a report can tell a permissions problem from a malformed patch from one
 * that would break the chain.
 */
public record ChainPatchHarnessResponse(
    String conversationId,
    SkillHarnessStatus status,
    String message,
    ChainPatchRefusal refusal,
    List<String> changedElementIds,
    List<String> failedElementIds,
    List<String> removedElementIds) {

  /** For a run that removed nothing. */
  public ChainPatchHarnessResponse(
      String conversationId,
      SkillHarnessStatus status,
      String message,
      ChainPatchRefusal refusal,
      List<String> changedElementIds,
      List<String> failedElementIds) {
    this(conversationId, status, message, refusal, changedElementIds, failedElementIds, List.of());
  }

  public ChainPatchHarnessResponse {
    refusal = refusal == null ? ChainPatchRefusal.NONE : refusal;
    changedElementIds = changedElementIds == null ? List.of() : List.copyOf(changedElementIds);
    failedElementIds = failedElementIds == null ? List.of() : List.copyOf(failedElementIds);
    removedElementIds = removedElementIds == null ? List.of() : List.copyOf(removedElementIds);
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
