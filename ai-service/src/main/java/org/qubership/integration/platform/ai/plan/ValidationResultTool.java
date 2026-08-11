package org.qubership.integration.platform.ai.plan;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logmanager.MDC;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

/**
 * LangChain4j tool that lets the validator agent persist a pre-build validation report.
 * Conversation id is taken from {@link org.qubership.integration.platform.ai.chat.ChatMdc}.
 */
@ApplicationScoped
public class ValidationResultTool {

  private static final Logger LOG = Logger.getLogger(ValidationResultTool.class);

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Plan validation did not capture a validation result. The agent must call"
          + " captureValidationResult with a non-blank summary before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Validation result already captured. Do not call captureValidationResult again;"
          + " finish this turn without further tool calls.";

  private final CaptureSession captureSession;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final CaptureRepairMessageBuilder repairMessageBuilder;

  @Inject
  ValidationResultTool(
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureRepairMessageBuilder repairMessageBuilder) {
    this.captureSession = captureSession;
    this.feedbackStore = feedbackStore;
    this.repairMessageBuilder = repairMessageBuilder;
  }

  @Tool("""
      Capture the pre-build validation report in the same turn once review is complete.
      Do not pass conversationId — the server binds the report to the current chat session\
       automatically.
      summary must be non-blank.
      Each issue must include severity and message.
      valid=false requires at least one issue.""")
  public String captureValidationResult(ValidationResult capture) {
    String conversationId = ChainPlanTool.resolveConversationId();
    String capabilityId = resolveCapabilityId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureValidationResult",
        conversationId,
        "preview=" + AiTraceLog.preview(summaryPreview(capture), 400));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        String message = "conversationId is required (no active chat session)";
        LOG.warnf("captureValidationResult: %s", message);
        return finish(conversationId, startMs, message);
      }
      if (capture == null) {
        String message = "capture is required";
        recordFailure(conversationId, capabilityId, message);
        return finish(conversationId, startMs, message);
      }

      String validationError = validateCapture(capture);
      if (validationError != null) {
        String message = repairMessageBuilder.validationResultMessage(validationError);
        LOG.warnf(
            "captureValidationResult: validation failed conversationId=%s error=%s",
            conversationId,
            validationError);
        recordFailure(conversationId, capabilityId, message);
        return finish(conversationId, startMs, message);
      }

      ValidationResult normalized = normalizeCapture(capture);
      CaptureKey key = CaptureKey.conversation(CaptureSlot.VALIDATION_RESULT, conversationId);
      String successMessage =
          "Validation result captured: "
              + AiTraceLog.preview(normalized.summary(), 160)
              + ". Do not call captureValidationResult again;"
              + " finish this turn without further tool calls.";
      String accepted =
          captureSession.accept(key, normalized, successMessage, DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearValidation(conversationId, capabilityId);

      LOG.infof(
          "captureValidationResult: stored report conversationId=%s valid=%s summary='%s'",
          conversationId,
          normalized.valid(),
          AiTraceLog.preview(normalized.summary(), 120));
      return finish(conversationId, startMs, accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureValidationResult", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing validation result: " + e.getMessage();
    }
  }

  private static String validateCapture(ValidationResult capture) {
    if (capture.summary() == null || capture.summary().isBlank()) {
      return "summary must be non-blank";
    }
    List<ValidationIssue> issues = capture.issues() != null ? capture.issues() : List.of();
    for (ValidationIssue issue : issues) {
      if (issue == null) {
        return "each issue must be a structured object";
      }
      if (issue.severity() == null) {
        return "each issue must include severity";
      }
      if (issue.message() == null || issue.message().isBlank()) {
        return "each issue must include message";
      }
    }
    if (!capture.valid() && issues.isEmpty()) {
      return "valid=false requires at least one issue";
    }
    return null;
  }

  private static ValidationResult normalizeCapture(ValidationResult capture) {
    List<ValidationIssue> issues = capture.issues() != null ? List.copyOf(capture.issues()) : List.of();
    boolean hasBlocker =
        issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.BLOCKER);
    if (!hasBlocker && !capture.valid() && !issues.isEmpty()) {
      return new ValidationResult(true, issues, capture.summary().trim());
    }
    return new ValidationResult(capture.valid(), issues, capture.summary().trim());
  }

  private void recordFailure(String conversationId, String capabilityId, String message) {
    if (capabilityId == null || capabilityId.isBlank()) {
      feedbackStore.recordPlanValidationFailure(conversationId, message);
      return;
    }
    feedbackStore.recordValidationFailure(conversationId, capabilityId, message);
  }

  private static String summaryPreview(ValidationResult capture) {
    if (capture == null) {
      return "null";
    }
    return "valid=" + capture.valid() + " summary=" + capture.summary();
  }

  private static String resolveCapabilityId() {
    Object mdcValue = MDC.get(CompilerSkillMdc.CAPABILITY_ID);
    if (mdcValue == null) {
      return null;
    }
    String capabilityId = mdcValue.toString().trim();
    return capabilityId.isEmpty() ? null : capabilityId;
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureValidationResult", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}
