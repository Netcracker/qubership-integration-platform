package org.qubership.integration.platform.ai.llm.tool;

import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import io.quarkiverse.langchain4j.DefaultToolExecutionErrorHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.capture.ToolArgumentsFailures;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureTool;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureTool.CaptureIssue;
import org.qubership.integration.platform.ai.plan.ProductRequirementBriefTool;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Mode;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanCaptureSession;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanCaptureTool;

/**
 * Turns a failed tool call into a tool result the model can act on. Without it a malformed call
 * ends the turn: argument binding runs before the tool body, so the model never learns that its
 * arguments were rejected and never gets the chance to send them again.
 *
 * <p>The handler answers the model, not the reader. Each message names the tool, says what the
 * service could not accept, and states the one thing to change.
 */
@ApplicationScoped
@DefaultToolExecutionErrorHandler
public class ToolCallErrorHandler implements ToolExecutionErrorHandler, ToolArgumentsErrorHandler {

  private static final Logger LOG = Logger.getLogger(ToolCallErrorHandler.class);

  @Inject ProductRequirementBriefTool productBriefTool;
  @Inject DesignPlanCaptureTool designPlanTool;

  /** Longest upstream message copied into the answer; beyond this the tail is dropped. */
  static final int MAX_REASON_CHARS = 500;

  @Override
  public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
    String toolName =
        context == null || context.toolExecutionRequest() == null
            ? "the tool"
            : context.toolExecutionRequest().name();
    LOG.warnf(
        "tool call failed, answering the model: tool=%s, error=%s",
        toolName, error == null ? "(none)" : error.toString());
    if (ChainSemanticCaptureTool.TOOL_NAME.equals(toolName)
        && ToolArgumentsFailures.isToolArgumentsFailure(error)) {
      Object memoryId = context.memoryId();
      String conversationId = memoryId == null
          ? ToolSession.resolveConversationId() : memoryId.toString();
      return ToolErrorHandlerResult.text(ChainSemanticCaptureTool.rejectArguments(
          conversationId,
          new CaptureIssue("INVALID_TYPE", "/capture",
              "Arguments do not match the design capture schema.")));
    }
    if ("captureRequirementBrief".equals(toolName)
        && ToolArgumentsFailures.isToolArgumentsFailure(error)
        && productBriefTool != null) {
      Object memoryId = context == null ? null : context.memoryId();
      String conversationId = memoryId == null
          ? ToolSession.resolveConversationId() : memoryId.toString();
      if (ProductCapabilityCaptureContext.binding(conversationId)
          .filter(binding -> binding.mode() == Mode.ANALYSIS).isPresent()) {
        return ToolErrorHandlerResult.text(productBriefTool.rejectArguments(
            conversationId,
            new StructuredCaptureArguments.Issue("INVALID_TYPE", "/capture",
                "Arguments do not match the requirement capture schema.")));
      }
    }
    if ("captureDesignPlan".equals(toolName)
        && ToolArgumentsFailures.isToolArgumentsFailure(error)
        && designPlanTool != null) {
      Object memoryId = context == null ? null : context.memoryId();
      String conversationId = memoryId == null
          ? ToolSession.resolveConversationId() : memoryId.toString();
      if (DesignPlanCaptureSession.binding(conversationId).isPresent()) {
        return ToolErrorHandlerResult.text(designPlanTool.rejectArguments(
            conversationId, new StructuredCaptureArguments.Issue(
                "INVALID_TYPE", "/capture", "Arguments do not match the plan capture schema.")));
      }
    }
    return ToolErrorHandlerResult.text(message(toolName, error));
  }

  static String message(String toolName, Throwable error) {
    String reason = reasonOf(error);
    if (looksLikeArgumentShape(reason)) {
      return "Tool "
          + toolName
          + " rejected the arguments: they do not match its schema. Send every parameter as JSON of"
          + " the declared type, never as a string holding JSON, then call "
          + toolName
          + " again.";
    }
    return "Tool " + toolName + " failed: " + reason + " Correct the call and try once more.";
  }

  private static boolean looksLikeArgumentShape(String reason) {
    String lower = reason.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("do not map onto the parameters")
        || lower.contains("no string-argument constructor")
        || lower.contains("cannot deserialize")
        || lower.contains("cannot construct instance");
  }

  private static String reasonOf(Throwable error) {
    Throwable root = error;
    while (root != null && root.getMessage() == null && root.getCause() != null) {
      root = root.getCause();
    }
    String message = root == null || root.getMessage() == null ? "" : root.getMessage().trim();
    if (message.isEmpty()) {
      return "the service could not run it.";
    }
    String trimmed =
        message.length() > MAX_REASON_CHARS ? message.substring(0, MAX_REASON_CHARS) : message;
    return trimmed.endsWith(".") ? trimmed : trimmed + ".";
  }
}
