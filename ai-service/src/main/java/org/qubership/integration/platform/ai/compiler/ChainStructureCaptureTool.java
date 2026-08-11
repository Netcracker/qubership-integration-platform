package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFeedbackChannel;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;

/** Captures typed {@link ChainStructure} output for planning flow. */
@ApplicationScoped
public class ChainStructureCaptureTool {

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Structure generation did not capture chain structure. The agent must call"
          + " captureChainStructure with a valid graph before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Chain structure already captured. Do not call captureChainStructure again;"
          + " finish this turn without further tool calls.";

  private static final Logger LOG = Logger.getLogger(ChainStructureCaptureTool.class);

  private final CaptureSession captureSession;
  private final ChainPlanGraphValidator graphValidator;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final CaptureToolOutcomeGateway outcomeGateway;
  private final ChainStructurePropertySanitizer propertySanitizer;

  @Inject
  ChainStructureCaptureTool(
      CaptureSession captureSession,
      ChainPlanGraphValidator graphValidator,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureToolOutcomeGateway outcomeGateway,
      ChainStructurePropertySanitizer propertySanitizer) {
    this.captureSession = captureSession;
    this.graphValidator = graphValidator;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
    this.outcomeGateway = outcomeGateway;
    this.propertySanitizer = propertySanitizer;
  }

  @Tool("""
      Capture chain structure with the first valid ChainPlanGraph revision.
      Do not pass conversationId — the server binds this capture to the current chat session.
      graph must be present and pass deterministic graph validation.
      Always copy configured http-trigger endpoint properties from ConfiguredTriggerSet
      (contextPath, httpMethodRestrict, externalRoute). Never emit properties:null on triggers.""")
  public String captureChainStructure(ChainStructure capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureChainStructure",
        conversationId,
        "preview=" + AiTraceLog.preview(previewCapture(capture), 400));
    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(conversationId, startMs, "conversationId is required (no active chat session)");
      }
      if (capture == null) {
        return finish(conversationId, startMs, "capture is required");
      }
      if (capture.graph() == null) {
        String message = "graph is required";
        return finish(
            conversationId,
            startMs,
            outcomeGateway.onFailure(
                CaptureFeedbackChannel.PLAN,
                conversationId,
                null,
                CaptureFailureKind.VALIDATION,
                CaptureFailureClass.CORRECTABLE,
                "captureChainStructure",
                capture,
                message));
      }
      ChainStructurePropertySanitizer.SanitizationResult sanitized =
          propertySanitizer.sanitize(capture);
      for (ChainStructurePropertySanitizer.RemovedProperty removed :
          sanitized.removedProperties()) {
        LOG.warnf(
            "Stripped schema-unknown structure property"
                + " conversationId=%s nodeId=%s elementType=%s key=%s",
            conversationId,
            removed.nodeId(),
            removed.elementType(),
            removed.key());
      }
      ChainStructure normalized =
          mergeConfiguredTriggerProperties(conversationId, sanitized.structure());
      List<String> errors = graphValidator.validate(normalized.graph());
      if (!errors.isEmpty()) {
        String message = "Structure validation failed:\n" + String.join("\n", errors);
        return finish(
            conversationId,
            startMs,
            outcomeGateway.onFailure(
                CaptureFeedbackChannel.PLAN,
                conversationId,
                null,
                CaptureFailureKind.VALIDATION,
                CaptureFailureClass.CORRECTABLE,
                "captureChainStructure",
                capture,
                message));
      }

      String accepted =
          captureSession.accept(
              CaptureKey.conversation(CaptureSlot.CHAIN_STRUCTURE, conversationId),
              normalized,
              "Chain structure captured. Do not call captureChainStructure again;"
                  + " finish this turn without further tool calls.",
              DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPlan(conversationId);
      finish(conversationId, startMs, accepted);
      throw new CaptureValidationException(accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureChainStructure", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing chain structure: " + e.getMessage();
    }
  }

  /**
   * Preserves {@link ConfiguredTriggerSet} endpoint fields when structure capture omits or nulls
   * trigger properties. Does not invent values — only copies already-captured trigger properties.
   */
  ChainStructure mergeConfiguredTriggerProperties(String conversationId, ChainStructure capture) {
    ConfiguredTriggerSet triggerSet =
        captureSession
            .get(
                CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId),
                ConfiguredTriggerSet.class)
            .orElse(null);
    ChainPlanGraph graph = capture == null ? null : capture.graph();
    ChainPlanGraph merged = ConfiguredTriggerSetGraphEnricher.enrich(graph, triggerSet);
    if (merged == null || merged == graph) {
      return capture;
    }
    LOG.infof(
        "Merged ConfiguredTriggerSet properties into chain structure conversationId=%s",
        conversationId);
    return new ChainStructure(
        merged, capture.sourceRequirementFactIds(), capture.knowledgeCitations());
  }

  private String previewCapture(ChainStructure capture) {
    if (capture == null) {
      return "null";
    }
    try {
      return objectMapper.writeValueAsString(capture);
    } catch (Exception e) {
      return capture.toString();
    }
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureChainStructure", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}
