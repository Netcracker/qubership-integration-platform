package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments;

/** Restricts HTTP trigger capture to approved identities and typed route exposure. */
@ApplicationScoped
public class HttpTriggerCaptureTool {

  public record Issue(String code, String path, String message) {}

  public record Result(
      boolean accepted, boolean changed, String nextAction, List<Issue> issues, String message) {}

  private final HttpTriggerCaptureAdapter adapter = new HttpTriggerCaptureAdapter();
  private final ConfiguredTriggerSetCaptureTool configuredTool;
  private final CaptureSession captureSession;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final ObjectMapper mapper;

  @Inject
  HttpTriggerCaptureTool(
      ConfiguredTriggerSetCaptureTool configuredTool,
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      ObjectMapper mapper) {
    this.configuredTool = configuredTool;
    this.captureSession = captureSession;
    this.feedbackStore = feedbackStore;
    this.mapper = mapper;
  }

  @Tool(
      value = """
          Capture one route exposure choice for every approved HTTP trigger. Copy roleId and
          semanticNodeId exactly from the prompt. externalRoute is a JSON boolean, never a
          string. Do not send paths, methods, labels, properties, or catalog identifiers; the
          server projects those from approved requirements. Call once, then finish.""",
      returnBehavior = ReturnBehavior.IMMEDIATE)
  public String captureHttpTriggers(HttpTriggerCapture capture) {
    String conversationId = CompilerGraphPatchTool.resolveConversationId();
    return encode(capture(capture, conversationId));
  }

  Result capture(HttpTriggerCapture capture, String conversationId) {
    HttpTriggerCaptureSession.Binding binding =
        HttpTriggerCaptureSession.get(conversationId).orElse(null);
    if (binding == null) {
      return rejected("CAPTURE_SESSION_NOT_FOUND", "/", "No active HTTP trigger capture.", true);
    }
    if (conversationId != null && captureSession.isPresent(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId))) {
      return rejected("DUPLICATE_CAPTURE", "/", "HTTP triggers already captured.", true);
    }
    try {
      String result = configuredTool.captureConfiguredTriggerSet(adapter.adapt(capture, binding));
      return rejected("TRIGGER_VALIDATION", "/endpoints", result, false);
    } catch (IllegalArgumentException error) {
      return reject(conversationId, "CAPTURE_SHAPE_INVALID", "/endpoints", error.getMessage());
    }
  }

  public String rejectArguments(String conversationId, StructuredCaptureArguments.Issue issue) {
    if (conversationId != null && captureSession.isPresent(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId))) {
      return encode(rejected("DUPLICATE_CAPTURE", "/", "HTTP triggers already captured.", true));
    }
    return encode(reject(conversationId, issue.code(), issue.path(), issue.message()));
  }

  private Result reject(String conversationId, String code, String path, String message) {
    if (HttpTriggerCaptureSession.get(conversationId).isEmpty()) {
      return rejected("CAPTURE_SESSION_NOT_FOUND", "/", "No active HTTP trigger capture.", true);
    }
    boolean repeated = feedbackStore.recordPlanValidationFailure(conversationId, message);
    if (repeated) {
      throw new CaptureValidationException(message);
    }
    return rejected(code, path, message, false);
  }

  private static Result rejected(String code, String path, String message, boolean terminal) {
    return new Result(false, false, terminal ? "STOP" : "REPAIR_CAPTURE",
        List.of(new Issue(code, path, message)), message);
  }

  private String encode(Result result) {
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize HTTP trigger capture result", error);
    }
  }
}
