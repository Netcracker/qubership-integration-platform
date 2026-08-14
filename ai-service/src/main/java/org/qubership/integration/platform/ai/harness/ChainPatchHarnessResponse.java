package org.qubership.integration.platform.ai.harness;

import java.util.List;

/**
 * Response body for {@code POST /api/v1/harness/chain-patch-run}.
 *
 * <p>{@code changedElementIds} and {@code failedElementIds} are catalog element ids, so the caller
 * can fetch each one back from the catalog without knowing how the patch pipeline named it
 * internally. {@code refusal} names which gate turned the patch away, so a report can tell a
 * permissions problem from a malformed patch from one that would break the chain.
 */
public record ChainPatchHarnessResponse(
    String conversationId,
    SkillHarnessStatus status,
    String message,
    ChainPatchRefusal refusal,
    List<String> changedElementIds,
    List<String> failedElementIds) {

  public ChainPatchHarnessResponse {
    refusal = refusal == null ? ChainPatchRefusal.NONE : refusal;
    changedElementIds = changedElementIds == null ? List.of() : List.copyOf(changedElementIds);
    failedElementIds = failedElementIds == null ? List.of() : List.copyOf(failedElementIds);
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
