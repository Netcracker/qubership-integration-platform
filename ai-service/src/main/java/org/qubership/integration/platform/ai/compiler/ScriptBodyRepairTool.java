package org.qubership.integration.platform.ai.compiler;

import dev.langchain4j.agent.tool.Tool;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrails;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/** Script-only repair tool for filling missing script bodies. */
@ApplicationScoped
public class ScriptBodyRepairTool {

  private static final Logger LOG = Logger.getLogger(ScriptBodyRepairTool.class);

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Compiler skill did not capture a script body repair patch. The agent must call"
          + " repairScriptBodies with scripts for every listed targetNodeId.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Script body repair patch already captured. Do not call repairScriptBodies again;"
          + " finish this turn without further tool calls.";

  static final String CAPTURE_SUCCESS_MESSAGE =
      "Script body repair patch captured. Do not call repairScriptBodies again;"
          + " finish this turn without further tool calls.";

  private final CaptureRouter captureRouter;
  private final CaptureSession captureSession;
  private final ChainPlanStore planStore;
  private final GeneratorReadinessEvaluator readinessEvaluator;
  private final GraphPatchApplier patchApplier;
  private final CaptureAttemptFeedbackStore feedbackStore;

  @Inject
  ScriptBodyRepairTool(
      CaptureRouter captureRouter,
      CaptureSession captureSession,
      ChainPlanStore planStore,
      GeneratorReadinessEvaluator readinessEvaluator,
      GraphPatchApplier patchApplier,
      CaptureAttemptFeedbackStore feedbackStore) {
    this.captureRouter = captureRouter;
    this.captureSession = captureSession;
    this.planStore = planStore;
    this.readinessEvaluator = readinessEvaluator;
    this.patchApplier = patchApplier;
    this.feedbackStore = feedbackStore;
  }

  @Tool(
      """
      Repair missing script node bodies by submitting scripts only.
      Submit exactly one script entry for each targetNodeId listed by the user message,
      including every branch response script (for example response-even/response-odd under
      if/else). Rationale-only or empty scripts payloads are rejected.
      Do not submit graph nodes, edges, chain fields, or properties other than script.
      Escape every double quote inside script strings. Prefer JsonOutput.toJson([error: msg])
      instead of embedding JSON object literals with quotes inside Groovy.
      """)
  @ToolInputGuardrails({ScriptBodyRepairArgumentsGuardrail.class})
  public String repairScriptBodies(ScriptBodyRepairCapture capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    String capabilityId = CompilerGraphPatchTool.resolveCapabilityId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "repairScriptBodies",
        conversationId,
        "capabilityId="
            + capabilityId
            + " patchId="
            + (capture != null ? capture.patchId() : "null")
            + " rationale="
            + AiTraceLog.preview(capture != null ? capture.rationale() : null, 120));
    String result;
    try {
      result = repair(conversationId, capabilityId, capture);
    } catch (CaptureValidationException e) {
      ToolTraceLog.logToolComplete(
          LOG,
          "repairScriptBodies",
          conversationId,
          System.currentTimeMillis() - startMs,
          e.getMessage());
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "repairScriptBodies", conversationId, System.currentTimeMillis() - startMs, e);
      result = "Error repairing script bodies: " + e.getMessage();
    }
    ToolTraceLog.logToolComplete(
        LOG, "repairScriptBodies", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private String repair(String conversationId, String capabilityId, ScriptBodyRepairCapture capture) {
    if (conversationId == null || conversationId.isBlank()) {
      return "conversationId is required (no active chat session)";
    }
    CaptureRoute route = captureRouter.routeFor(capabilityId);
    if (route.captureTool() != CaptureTool.REPAIR_SCRIPT_BODIES) {
      return "Script body repair is only allowed when runtime.capture.tool is repairScriptBodies.";
    }
    CaptureKey key =
        CaptureKey.capability(CaptureSlot.SCRIPT_BODY_REPAIR, conversationId, capabilityId);
    ChainPlanGraph base = planStore.get(conversationId).orElse(null);
    if (base == null) {
      return recordFailure(conversationId, capabilityId, "CHAIN_PLAN_GRAPH is required");
    }
    List<String> missingNodeIds = readinessEvaluator.scriptNodesMissingBody(base);
    if (missingNodeIds.isEmpty()) {
      return "No script nodes need repair.";
    }
    String validationError = validateCapture(capture, missingNodeIds);
    if (validationError != null) {
      return recordFailure(conversationId, capabilityId, validationError);
    }

    Map<String, ScriptBodyEntry> byNodeId = scriptsByNodeId(capture.scripts());
    List<PropertyPatch> propertyPatches = new ArrayList<>(missingNodeIds.size());
    for (String nodeId : missingNodeIds) {
      GraphPatchOperation operation = hasScriptProperty(base, nodeId)
          ? GraphPatchOperation.UPDATE
          : GraphPatchOperation.ADD;
      propertyPatches.add(
          new PropertyPatch(
              operation,
              nodeId,
              new PlanProperty("script", byNodeId.get(nodeId).script().trim())));
    }

    GraphPatch patch =
        new GraphPatch(
            capture.patchId(),
            capabilityId,
            List.of(),
            List.of(),
            propertyPatches,
            List.of(),
            List.of(),
            capture.rationale());
    GraphPatchApplyResult applied = patchApplier.apply(base, patch);
    if (!applied.validationResult().valid()) {
      return recordFailure(
          conversationId, capabilityId, "Script repair patch failed: " + applied.validationResult().summary());
    }
    List<String> stillMissing = readinessEvaluator.scriptNodesMissingBody(applied.graph());
    if (!stillMissing.isEmpty()) {
      return recordFailure(
          conversationId,
          capabilityId,
          "Script repair patch is incomplete. Missing script node ids: "
              + String.join(", ", stillMissing)
              + ".");
    }

    String accepted =
        captureSession.accept(key, patch, CAPTURE_SUCCESS_MESSAGE, DUPLICATE_CAPTURE_MESSAGE);
    feedbackStore.clearPatch(conversationId, capabilityId);
    // Terminal signal: PreventsErrorHandlerExecution aborts the streaming tool loop so
    // CaptureRepairRunner can complete and harvest can run without waiting for an LLM end-turn.
    throw new CaptureValidationException(accepted);
  }

  private static String validateCapture(ScriptBodyRepairCapture capture, List<String> missingNodeIds) {
    if (capture == null || capture.scripts() == null || capture.scripts().isEmpty()) {
      return "Script body repair failed: scripts are required for node ids "
          + String.join(", ", missingNodeIds)
          + ".";
    }
    Map<String, ScriptBodyEntry> byNodeId = scriptsByNodeId(capture.scripts());
    if (capture.scripts().size() > byNodeId.size()) {
      LOG.debugf(
          "repairScriptBodies: ignored %d duplicate targetNodeId entries (last wins)",
          capture.scripts().size() - byNodeId.size());
    }
    List<String> extra = byNodeId.keySet().stream().filter(id -> !missingNodeIds.contains(id)).toList();
    if (!extra.isEmpty()) {
      return "Script body repair failed: targetNodeId is not allowed: "
          + String.join(", ", extra)
          + ". Allowed missing ids: "
          + String.join(", ", missingNodeIds)
          + ". Submit scripts[{targetNodeId, script}] only for those ids.";
    }
    List<String> missing = missingNodeIds.stream().filter(id -> !byNodeId.containsKey(id)).toList();
    if (!missing.isEmpty()) {
      return "Script body repair failed: missing scripts for node ids " + String.join(", ", missing) + ".";
    }
    List<String> blank =
        byNodeId.values().stream()
            .filter(
                entry ->
                    entry.script() == null
                        || entry.script().isBlank()
                        || ScriptBodyPromptRedaction.isOmittedPlaceholder(entry.script()))
            .map(ScriptBodyEntry::targetNodeId)
            .toList();
    if (!blank.isEmpty()) {
      return "Script body repair failed: script body is blank for node ids " + String.join(", ", blank) + ".";
    }
    return null;
  }

  private static Map<String, ScriptBodyEntry> scriptsByNodeId(List<ScriptBodyEntry> scripts) {
    Map<String, ScriptBodyEntry> byNodeId = new LinkedHashMap<>();
    if (scripts == null) {
      return byNodeId;
    }
    for (ScriptBodyEntry entry : scripts) {
      if (entry == null || entry.targetNodeId() == null || entry.targetNodeId().isBlank()) {
        continue;
      }
      byNodeId.put(entry.targetNodeId(), entry);
    }
    return byNodeId;
  }

  private static boolean hasScriptProperty(ChainPlanGraph graph, String nodeId) {
    if (graph.nodes() == null) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (!nodeId.equals(node.nodeId()) || node.properties() == null) {
        continue;
      }
      for (PlanProperty property : node.properties()) {
        if ("script".equals(property.key())) {
          return true;
        }
      }
    }
    return false;
  }

  private String recordFailure(String conversationId, String capabilityId, String message) {
    boolean repeated =
        feedbackStore.recordPatchValidationFailure(conversationId, capabilityId, message);
    if (repeated) {
      throw new CaptureValidationException(
          "Repeated script body repair validation failure: " + message);
    }
    return message;
  }
}
