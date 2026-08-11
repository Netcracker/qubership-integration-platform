package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;

/** Captures typed {@link ConfiguredTriggerSet} output for planning flow. */
@ApplicationScoped
public class ConfiguredTriggerSetCaptureTool {

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Trigger generation did not capture configured triggers. The agent must call"
          + " captureConfiguredTriggerSet with at least one trigger before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Configured trigger set already captured. Do not call captureConfiguredTriggerSet again;"
          + " finish this turn without further tool calls.";

  private static final Logger LOG = Logger.getLogger(ConfiguredTriggerSetCaptureTool.class);

  private final CaptureSession captureSession;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;

  @Inject
  ConfiguredTriggerSetCaptureTool(
      CaptureSession captureSession,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore) {
    this.captureSession = captureSession;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
  }

  @Tool("""
      Capture configured trigger set in the same turn after trigger generation is complete.
      Do not pass conversationId — the server binds this capture to the current chat session.
      triggers must include at least one configured trigger.""")
  public String captureConfiguredTriggerSet(ConfiguredTriggerSet capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureConfiguredTriggerSet",
        conversationId,
        "preview=" + AiTraceLog.preview(previewCapture(capture), 400));
    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(conversationId, startMs, "conversationId is required (no active chat session)");
      }
      if (capture == null) {
        return finish(conversationId, startMs, "capture is required");
      }
      if (capture.triggers() == null || capture.triggers().isEmpty()) {
        String message = "triggers must include at least one configured trigger";
        boolean repeated = feedbackStore.recordPlanValidationFailure(conversationId, message);
        if (repeated) {
          throw new CaptureValidationException(message);
        }
        return finish(conversationId, startMs, message);
      }
      String accepted =
          captureSession.accept(
              CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId),
              capture,
              "Configured trigger set captured. Do not call captureConfiguredTriggerSet again;"
                  + " finish this turn without further tool calls.",
              DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPlan(conversationId);
      finish(conversationId, startMs, accepted);
      throw new CaptureValidationException(accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG,
          "captureConfiguredTriggerSet",
          conversationId,
          System.currentTimeMillis() - startMs,
          e);
      return "Error capturing configured trigger set: " + e.getMessage();
    }
  }

  private String previewCapture(ConfiguredTriggerSet capture) {
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
        LOG,
        "captureConfiguredTriggerSet",
        conversationId,
        System.currentTimeMillis() - startMs,
        result);
    return result;
  }
}
