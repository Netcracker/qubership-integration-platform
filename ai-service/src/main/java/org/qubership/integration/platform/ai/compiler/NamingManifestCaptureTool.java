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
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;

/** Captures typed {@link NamingManifest} output for planning flow. */
@ApplicationScoped
public class NamingManifestCaptureTool {

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Naming generation did not capture a naming manifest. The agent must call"
          + " captureNamingManifest with a non-blank chainName before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Naming manifest already captured. Do not call captureNamingManifest again;"
          + " finish this turn without further tool calls.";

  private static final Logger LOG = Logger.getLogger(NamingManifestCaptureTool.class);

  private final CaptureSession captureSession;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;

  @Inject
  NamingManifestCaptureTool(
      CaptureSession captureSession,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore) {
    this.captureSession = captureSession;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
  }

  @Tool("""
      Capture the naming manifest in the same turn after naming generation is complete.
      Do not pass conversationId — the server binds this capture to the current chat session.
      chainName must be non-blank.""")
  public String captureNamingManifest(NamingManifest capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureNamingManifest",
        conversationId,
        "preview=" + AiTraceLog.previewJson(objectMapper, capture, 400));
    try {
      if (conversationId == null || conversationId.isBlank()) {
        return finish(conversationId, startMs, "conversationId is required (no active chat session)");
      }
      if (capture == null) {
        return finish(conversationId, startMs, "capture is required");
      }
      if (capture.chainName() == null || capture.chainName().isBlank()) {
        String message = "chainName must be non-blank";
        boolean repeated = feedbackStore.recordPlanValidationFailure(conversationId, message);
        if (repeated) {
          throw new CaptureValidationException(message);
        }
        return finish(conversationId, startMs, message);
      }
      NamingManifest normalized =
          new NamingManifest(
              capture.schemaVersion(),
              capture.chainName().trim(),
              capture.labelsByRoleId(),
              capture.sourceRequirementFactIds(),
              capture.knowledgeCitations());
      String accepted =
          captureSession.accept(
              CaptureKey.conversation(CaptureSlot.NAMING_MANIFEST, conversationId),
              normalized,
              "Naming manifest captured. Do not call captureNamingManifest again;"
                  + " finish this turn without further tool calls.",
              DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPlan(conversationId);
      finish(conversationId, startMs, accepted);
      throw new CaptureValidationException(accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureNamingManifest", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing naming manifest: " + e.getMessage();
    }
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureNamingManifest", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}
