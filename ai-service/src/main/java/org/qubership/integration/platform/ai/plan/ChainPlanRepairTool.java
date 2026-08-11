package org.qubership.integration.platform.ai.plan;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;

/** Edge-only tool for repairing invalid chain plan drafts. */
@ApplicationScoped
public class ChainPlanRepairTool {

  private static final Logger LOG = Logger.getLogger(ChainPlanRepairTool.class);
  private static final String OWNER = "chain-plan-repair";

  private final ChainPlanRepairDraftStore draftStore;
  private final ChainPlanStore planStore;
  private final ChainPlanGraphValidator validator;
  private final GraphPatchApplier patchApplier;
  private final CaptureAttemptFeedbackStore feedbackStore;

  @Inject
  ChainPlanRepairTool(
      ChainPlanRepairDraftStore draftStore,
      ChainPlanStore planStore,
      ChainPlanGraphValidator validator,
      GraphPatchApplier patchApplier,
      CaptureAttemptFeedbackStore feedbackStore) {
    this.draftStore = draftStore;
    this.planStore = planStore;
    this.validator = validator;
    this.patchApplier = patchApplier;
    this.feedbackStore = feedbackStore;
  }

  @Tool(
      """
      Repair the last invalid ChainPlanGraph draft by submitting edgePatches only.
      Use ADD edge patches only for MISSING_SIBLING_EXECUTION_EDGE diagnostics.
      Use UPDATE or REMOVE edge patches only for BAD_EDGE_REFERENCE diagnostics.
      Do not submit nodePatches, propertyPatches, chainPatches, or a full ChainPlanGraph.
      """)
  public String repairChainPlanPatch(ChainPlanRepairPatchCapture patch) {
    String conversationId = ChainPlanTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "repairChainPlanPatch",
        conversationId,
        "patchId="
            + (patch != null ? patch.patchId() : "null")
            + " rationale="
            + AiTraceLog.preview(patch != null ? patch.rationale() : null, 120));
    String result;
    try {
      result = repair(conversationId, patch);
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "repairChainPlanPatch", conversationId, System.currentTimeMillis() - startMs, e);
      result = "Error repairing plan: " + e.getMessage();
    }
    ToolTraceLog.logToolComplete(
        LOG, "repairChainPlanPatch", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private String repair(String conversationId, ChainPlanRepairPatchCapture patch) {
    if (conversationId == null || conversationId.isBlank()) {
      return "conversationId is required (no active chat session)";
    }
    ChainPlanGraph draft = draftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return "No invalid chain plan draft is available for repair.";
    }
    if (patch == null || patch.edgePatches() == null || patch.edgePatches().isEmpty()) {
      return recordFailure(conversationId, "Plan repair failed: edgePatches are required.");
    }
    List<ChainPlanRepairIssue> issues = validator.diagnoseForRepair(draft);
    String operationError = validateOperations(patch.edgePatches(), issues);
    if (operationError != null) {
      return recordFailure(conversationId, operationError);
    }

    GraphPatch graphPatch =
        new GraphPatch(
            patch.patchId(),
            OWNER,
            null,
            patch.edgePatches(),
            null,
            null,
            List.of(),
            patch.rationale());
    GraphPatchApplyResult applied = patchApplier.apply(draft, graphPatch);
    if (!applied.validationResult().valid()) {
      return recordFailure(
          conversationId, "Plan repair patch failed: " + applied.validationResult().summary());
    }

    List<String> errors = validator.validate(applied.graph());
    if (!errors.isEmpty()) {
      draftStore.put(conversationId, applied.graph());
      return recordFailure(conversationId, "Plan validation failed:\n" + String.join("\n", errors));
    }

    planStore.put(conversationId, applied.graph());
    draftStore.remove(conversationId);
    feedbackStore.clearPlan(conversationId);
    return "Plan repaired and captured.";
  }

  private String validateOperations(
      List<EdgePatch> edgePatches, List<ChainPlanRepairIssue> issues) {
    boolean hasMissingSibling =
        issues.stream().anyMatch(issue -> "MISSING_SIBLING_EXECUTION_EDGE".equals(issue.code()));
    boolean hasBadEdgeRef =
        issues.stream().anyMatch(issue -> "BAD_EDGE_REFERENCE".equals(issue.code()));
    boolean hasAdd = false;
    boolean hasUpdateOrRemove = false;
    for (EdgePatch edgePatch : edgePatches) {
      if (edgePatch == null || edgePatch.operation() == null) {
        return "Plan repair failed: every edge patch needs an operation.";
      }
      if (edgePatch.operation() == GraphPatchOperation.ADD) {
        hasAdd = true;
      } else if (edgePatch.operation() == GraphPatchOperation.UPDATE
          || edgePatch.operation() == GraphPatchOperation.REMOVE) {
        hasUpdateOrRemove = true;
      }
    }
    if (hasAdd && hasUpdateOrRemove) {
      return "Plan repair failed: do not mix ADD with UPDATE or REMOVE in one repair patch.";
    }
    if (hasAdd && (!hasMissingSibling || hasBadEdgeRef)) {
      return "Plan repair failed: ADD edge patches are only allowed for missing sibling execution edges.";
    }
    if (hasUpdateOrRemove && (!hasBadEdgeRef || hasMissingSibling)) {
      return "Plan repair failed: UPDATE and REMOVE edge patches are only allowed for bad edge references.";
    }
    return null;
  }

  private String recordFailure(String conversationId, String message) {
    boolean repeated = feedbackStore.recordPlanValidationFailure(conversationId, message);
    if (repeated) {
      return message + "\nThe previous repair produced the same failure. Submit a different minimal edge patch.";
    }
    return message;
  }
}
