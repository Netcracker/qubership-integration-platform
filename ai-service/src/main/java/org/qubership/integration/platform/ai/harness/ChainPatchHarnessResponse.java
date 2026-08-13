package org.qubership.integration.platform.ai.harness;

import java.util.List;

/**
 * Response body for {@code POST /api/v1/harness/chain-patch-run}.
 *
 * <p>{@code changedElementIds} and {@code failedElementIds} are catalog element ids, so the caller
 * can fetch each one back from the catalog without knowing how the patch pipeline named it
 * internally. {@code scopeViolation} is set when the patch was refused by the ownership policy
 * rather than by an ordinary write failure, so a report can tell the two apart.
 */
public record ChainPatchHarnessResponse(
    String conversationId,
    SkillHarnessStatus status,
    String message,
    boolean scopeViolation,
    List<String> changedElementIds,
    List<String> failedElementIds) {

  public ChainPatchHarnessResponse {
    changedElementIds = changedElementIds == null ? List.of() : List.copyOf(changedElementIds);
    failedElementIds = failedElementIds == null ? List.of() : List.copyOf(failedElementIds);
  }
}
