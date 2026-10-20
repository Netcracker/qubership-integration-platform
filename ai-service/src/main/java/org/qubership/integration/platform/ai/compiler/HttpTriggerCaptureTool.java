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
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments;

/** Restricts HTTP trigger capture to approved identities and typed route exposure. */
@ApplicationScoped
public class HttpTriggerCaptureTool {

  public record Issue(String code, String path, String message) {}

  public record Result(
      boolean accepted, boolean changed, String nextAction, List<Issue> issues, String message,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference evidenceReference) {
    public Result(boolean accepted, boolean changed, String nextAction, List<Issue> issues, String message) {
      this(accepted, changed, nextAction, issues, message, null);
    }
  }

  private final HttpTriggerCaptureAdapter adapter = new HttpTriggerCaptureAdapter();
  private final ConfiguredTriggerSetCaptureTool configuredTool;
  private final CaptureSession captureSession;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final ObjectMapper mapper;

  @Inject org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl recoveryControl;
  @Inject CaptureToolOutcomeGateway outcomeGateway;

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
    String capabilityId = CompilerGraphPatchTool.resolveCapabilityId();
    if (recoveryControl != null) {
      capture = recoveryControl.httpCapture(conversationId, capabilityId, capture);
    }
    feedbackStore.beginProposal(conversationId, capabilityId,
        "captureHttpTriggers", capture);
    try {
      String result = configuredTool.captureConfiguredTriggerSet(adapter.adapt(capture, binding));
      return rejected("TRIGGER_VALIDATION", "/endpoints", result, false);
    } catch (IllegalArgumentException error) {
      return reject(conversationId, "CAPTURE_SHAPE_INVALID", "/endpoints", error.getMessage());
    } finally {
      if (recoveryControl != null) {
        var accepted = captureSession.get(
            CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId),
            org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet.class);
        if (accepted.isPresent()) {
          recoveryControl.acceptedHttpCapture(conversationId, capabilityId, capture, accepted.orElseThrow());
        }
      }
    }
  }

  public String rejectArguments(String conversationId, StructuredCaptureArguments.Issue issue) {
    if (conversationId != null && captureSession.isPresent(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId))) {
      return encode(rejected("DUPLICATE_CAPTURE", "/", "HTTP triggers already captured.", true));
    }
    return encode(reject(conversationId, issue.code(), issue.path(), issue.message(), approvedEntity(conversationId, issue, null)));
  }

  public String rejectArguments(String conversationId, StructuredCaptureArguments.Issue issue,
      dev.langchain4j.agent.tool.ToolExecutionRequest request) {
    feedbackStore.beginRejectedProposal(conversationId, CompilerGraphPatchTool.resolveCapabilityId(),
        "captureHttpTriggers", request == null ? null : request.arguments(), request == null ? null : request.id());
    if (conversationId != null && captureSession.isPresent(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, conversationId))) {
      return encode(rejected("DUPLICATE_CAPTURE", "/", "HTTP triggers already captured.", true));
    }
    return encode(reject(conversationId, issue.code(), issue.path(), issue.message(), approvedEntity(conversationId, issue, request)));
  }

  private String approvedEntity(String conversationId, StructuredCaptureArguments.Issue issue,
      dev.langchain4j.agent.tool.ToolExecutionRequest request) {
    var binding = HttpTriggerCaptureSession.get(conversationId).orElse(null);
    if (binding == null) return null;
    var roles = binding.skeleton().entryPointRoleIds();
    if (roles.size() == 1) return roles.getFirst();
    if (request != null) {
      try {
        var root = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .readTree(request.arguments());
        var parts = issue.path().split("/");
        for (int index = 0; index + 1 < parts.length; index++) {
          if (!"endpoints".equals(parts[index])) continue;
          String role = root.path("capture").path("endpoints").path(Integer.parseInt(parts[index + 1])).path("roleId").asText();
          if (roles.contains(role)) return role;
        }
      } catch (java.io.IOException | IllegalArgumentException ambiguousProposal) {
        return null;
      }
    }
    return null;
  }

  private Result reject(String conversationId, String code, String path, String message) {
    return reject(conversationId, code, path, message, null);
  }

  private Result reject(String conversationId, String code, String path, String message, String entity) {
    var binding = HttpTriggerCaptureSession.get(conversationId).orElse(null);
    if (binding == null) {
      return rejected("CAPTURE_SESSION_NOT_FOUND", "/", "No active HTTP trigger capture.", true);
    }
    message += " Approved roleIds: " + binding.skeleton().entryPointRoleIds()
        + "; approved semanticNodeIds: " + binding.revision().entryPoints().stream()
            .map(org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint::triggerNodeId).toList()
        + ". Copy these identifiers exactly; paths and labels are not identifiers. externalRoute must be a JSON boolean.";
    var gateway = outcomeGateway == null
        ? new CaptureToolOutcomeGateway(feedbackStore.fingerprintStore(), feedbackStore) : outcomeGateway;
    gateway.onTypedPlanFailure(
        conversationId, CompilerGraphPatchTool.resolveCapabilityId(), "captureHttpTriggers",
        new CaptureToolOutcomeGateway.TypedIssue(code, path, message, entity));
    return new Result(false, false, "REPAIR_CAPTURE", List.of(new Issue(code, path, message)), message,
        feedbackStore.lastPlanFailure(conversationId).map(
            org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback::evidenceReference).orElse(null));
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
