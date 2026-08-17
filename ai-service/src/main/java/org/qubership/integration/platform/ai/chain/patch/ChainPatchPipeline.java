package org.qubership.integration.platform.ai.chain.patch;

import java.util.List;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;

/**
 * The execution context an existing-chain patch is applied under, shared by the interactive
 * scenario and the regression harness so ownership is bounded identically for both.
 */
public final class ChainPatchPipeline {

  public static final String OWNER = "chain-patch";

  private ChainPatchPipeline() {}

  public static GraphPatchExecutionContext executionContext(
      ImportedChainPlan imported, String chainId, GraphPatch patch, ChainPatchOwnership ownership) {
    return executionContext(imported, chainId, patch, ownership, false);
  }

  public static GraphPatchExecutionContext executionContext(
      ImportedChainPlan imported,
      String chainId,
      GraphPatch patch,
      ChainPatchOwnership ownership,
      boolean mayRemove) {
    return new GraphPatchExecutionContext(
        chainId,
        OWNER,
        null,
        imported.baseGraphDigest(),
        null,
        null,
        null,
        List.of(),
        imported.graph(),
        ownership.forChain(imported.graph(), patch, mayRemove),
        null);
  }

  /**
   * Whether a failed {@link GraphPatchApplyResult} was refused by the ownership policy, as opposed
   * to a structural block a later stage (e.g. a missing edge id) raised after ownership already
   * passed. The two read very differently to a reader: one names a permission the patch lacks, the
   * other names something wrong with the patch itself.
   */
  public static boolean isOwnershipViolation(GraphPatchApplyResult applied) {
    return applied.validationResult().issues().stream()
        .anyMatch(
            issue ->
                GraphPatchOwnershipValidator.OWNERSHIP_VIOLATION_ISSUE_ID.equals(issue.issueId()));
  }
}
