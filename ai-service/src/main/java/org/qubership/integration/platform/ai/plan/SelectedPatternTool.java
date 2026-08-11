package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldFillPolicy;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFeedbackChannel;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;

/**
 * LangChain4j tool that lets the pattern selector agent persist a golden pattern choice.
 * Conversation id is taken from {@link ChainPlanTool#resolveConversationId()}.
 */
@ApplicationScoped
public class SelectedPatternTool {

  public static final String PATTERN_SELECTOR_SKILL_ID = "cip-pattern-selector";

  private static final Logger LOG = Logger.getLogger(SelectedPatternTool.class);
  private static final Pattern GOLDEN_PATTERN_ID = Pattern.compile("^GP-0[1-7]$");

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Pattern selection did not capture a golden pattern. The agent must call"
          + " captureSelectedPattern with patternId GP-01..GP-07 before finishing.";

  public static final String SKELETON_REQUIRED_MESSAGE =
      "Pattern selection did not capture an element skeleton. Call captureSelectedPattern with"
          + " elementSkeleton (selectedPatternId and at least one elementRoles entry) before"
          + " finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Selected pattern already captured. Do not call captureSelectedPattern or knowledge lookup"
          + " tools again; finish this turn without further tool calls.";

  private final CaptureSession captureSession;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final CaptureFieldFillPolicy fillPolicy;
  private final CaptureToolOutcomeGateway outcomeGateway;

  @Inject
  SelectedPatternTool(
      CaptureSession captureSession,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureFieldFillPolicy fillPolicy,
      CaptureToolOutcomeGateway outcomeGateway) {
    this.captureSession = captureSession;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
    this.fillPolicy = fillPolicy;
    this.outcomeGateway = outcomeGateway;
  }

  @Tool("""
      Capture the selected golden pattern in the same turn once D-017 selection is complete.
      Do not pass conversationId — the server binds the selection to the current chat session\
       automatically.
      patternId must be GP-01 through GP-07. reason and summary cannot both be blank.
      elementSkeleton is required: selectedPatternId must match patternId, and elementRoles must\
       list at least one role with roleId and elementType (types and hierarchy only).
      summary should describe the element skeleton (types and hierarchy only).
      Minimal example:
      {
        "patternId": "GP-01",
        "name": "Protected Request-Response",
        "reason": "Production HTTP API with backend service calls",
        "summary": "http-trigger -> try-catch-finally-2 -> try-2 holds service-call and script",
        "requiredCapabilities": [],
        "elementSkeleton": {
          "selectedPatternId": "GP-01",
          "entryPointRoleIds": ["http-entry"],
          "elementRoles": [
            {"roleId": "http-entry", "elementType": "http-trigger"},
            {"roleId": "try-catch", "elementType": "try-catch-finally-2"},
            {"roleId": "try-body", "elementType": "try-2"},
            {"roleId": "script", "elementType": "script"}
          ]
        }
      }""")
  public String captureSelectedPattern(SelectedPatternCapture capture) {
    String conversationId = ChainPlanTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    String preview = previewCapture(capture);
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureSelectedPattern",
        conversationId,
        "preview=" + AiTraceLog.preview(preview, 400));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        String message = "conversationId is required (no active chat session)";
        LOG.warnf("captureSelectedPattern: %s", message);
        return finish(conversationId, startMs, message);
      }
      if (capture == null) {
        String message = "capture is required";
        LOG.warnf("captureSelectedPattern: %s conversationId=%s", message, conversationId);
        return finish(conversationId, startMs, message);
      }

      capture = (SelectedPatternCapture) fillPolicy.apply(capture);

      String patternValidationError = validatePattern(capture);
      if (patternValidationError != null) {
        LOG.warnf("captureSelectedPattern: validation failed conversationId=%s", conversationId);
        return finish(
            conversationId,
            startMs,
            validationFailure(conversationId, capture, patternValidationError));
      }

      SelectedPattern pattern = toSelectedPattern(capture);
      ElementSkeleton skeleton = capture.elementSkeleton();
      if (skeleton == null) {
        LOG.warnf(
            "captureSelectedPattern: elementSkeleton missing conversationId=%s", conversationId);
        return finish(
            conversationId,
            startMs,
            validationFailure(conversationId, capture, SKELETON_REQUIRED_MESSAGE));
      }
      String skeletonValidationError = validateSkeleton(capture.patternId(), skeleton);
      if (skeletonValidationError != null) {
        LOG.warnf(
            "captureSelectedPattern: skeleton validation failed conversationId=%s",
            conversationId);
        return finish(
            conversationId,
            startMs,
            validationFailure(conversationId, capture, skeletonValidationError));
      }
      String successMessage =
          "Selected pattern captured: "
              + pattern.patternId()
              + " ("
              + AiTraceLog.preview(pattern.name(), 80)
              + "). Do not call captureSelectedPattern or knowledge lookup tools again;"
              + " finish this turn without further tool calls.";
      Map<CaptureKey, Object> values = new LinkedHashMap<>();
      values.put(CaptureKey.conversation(CaptureSlot.SELECTED_PATTERN, conversationId), pattern);
      values.put(
          CaptureKey.conversation(CaptureSlot.ELEMENT_SKELETON, conversationId),
          normalizeSkeleton(skeleton));
      String accepted = captureSession.acceptAll(values, successMessage, DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPlan(conversationId);

      LOG.infof(
          "captureSelectedPattern: stored pattern conversationId=%s patternId=%s",
          conversationId,
          pattern.patternId());
      // Terminal signal: PreventsErrorHandlerExecution aborts the streaming tool loop so
      // CaptureRepairRunner can complete and harvest can run without waiting for an LLM end-turn.
      finish(conversationId, startMs, accepted);
      throw new CaptureValidationException(accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureSelectedPattern", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing selected pattern: " + e.getMessage();
    }
  }

  private String validationFailure(
      String conversationId, SelectedPatternCapture capture, String message) {
    List<CaptureFieldHint> hints = fillPolicy.hintsWhenStillBlank(capture);
    return outcomeGateway.onFailure(
        CaptureFeedbackChannel.PLAN,
        conversationId,
        null,
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        "captureSelectedPattern",
        capture,
        message,
        hints);
  }

  private static String validatePattern(SelectedPatternCapture capture) {
    if (!hasText(capture.patternId()) || !GOLDEN_PATTERN_ID.matcher(capture.patternId().trim()).matches()) {
      return "patternId must be GP-01 through GP-07";
    }
    if (!hasText(capture.reason()) && !hasText(capture.summary())) {
      return "reason and summary cannot both be blank";
    }
    return null;
  }

  private static String validateSkeleton(String patternId, ElementSkeleton skeleton) {
    if (!hasText(skeleton.selectedPatternId())) {
      return "elementSkeleton.selectedPatternId is required";
    }
    if (!patternId.trim().equals(skeleton.selectedPatternId().trim())) {
      return "elementSkeleton.selectedPatternId must match patternId";
    }
    if (skeleton.elementRoles() == null || skeleton.elementRoles().isEmpty()) {
      return "elementSkeleton.elementRoles must contain at least one role";
    }
    for (ElementRole role : skeleton.elementRoles()) {
      if (role == null || !hasText(role.roleId()) || !hasText(role.elementType())) {
        return "elementSkeleton.elementRoles entries must include roleId and elementType";
      }
    }
    return null;
  }

  private static SelectedPattern toSelectedPattern(SelectedPatternCapture capture) {
    List<String> capabilities =
        capture.requiredCapabilities() == null ? List.of() : List.copyOf(capture.requiredCapabilities());
    return new SelectedPattern(
        capture.patternId().trim(),
        nullToEmpty(capture.name()),
        nullToEmpty(capture.reason()),
        null,
        capabilities,
        nullToEmpty(capture.summary()));
  }

  private static ElementSkeleton normalizeSkeleton(ElementSkeleton skeleton) {
    return new ElementSkeleton(
        skeleton.schemaVersion(),
        nullToEmpty(skeleton.selectedPatternId()),
        skeleton.entryPointRoleIds(),
        skeleton.elementRoles(),
        skeleton.cardinalityObligations(),
        skeleton.requiredCapabilities(),
        skeleton.sourceRequirementFactIds(),
        skeleton.knowledgeCitations());
  }

  private static String nullToEmpty(String value) {
    return value != null ? value.trim() : "";
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private String previewCapture(SelectedPatternCapture capture) {
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
        LOG, "captureSelectedPattern", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}
