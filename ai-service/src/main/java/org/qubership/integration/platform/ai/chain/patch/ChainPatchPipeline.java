package org.qubership.integration.platform.ai.chain.patch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;

/**
 * Assembles a chain patch attempt from a model capture, shared by every caller that drives the
 * COMPARE_AND_PATCH pipeline: the interactive scenario and the regression harness. One assembly
 * path keeps ownership and shape validation identical regardless of who is asking for the change.
 */
public final class ChainPatchPipeline {

  public static final String OWNER = "chain-patch";

  private ChainPatchPipeline() {}

  public static GraphPatch toGraphPatch(ChainPatchCapture capture) {
    return new GraphPatch(
        capture.patchId(),
        OWNER,
        capture.nodePatches(),
        capture.edgePatches(),
        capture.propertyPatches(),
        null,
        List.of(),
        capture.rationale());
  }

  public static GraphPatchExecutionContext executionContext(
      ImportedChainPlan imported, String chainId, GraphPatch patch, ChainPatchOwnership ownership) {
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
        ownership.forChain(imported.graph(), patch),
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

  public static String buildPatchRequest(
      ObjectMapper objectMapper, ChainPlanGraph graph, String userMessage) {
    String graphJson;
    try {
      graphJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(graph);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot render the chain graph for the model", e);
    }
    String body =
        """
        Change this chain as the user asks.

        User request:
        %s

        Chain graph (the current state of the chain; node ids are catalog element ids):
        %s
        """
            .formatted(userMessage, graphJson);
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
  }
}
