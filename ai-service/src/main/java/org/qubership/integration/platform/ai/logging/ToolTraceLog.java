package org.qubership.integration.platform.ai.logging;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

/** Grep-friendly structured logging for LangChain4j {@code @Tool} invocations. */
public final class ToolTraceLog {

  private static final String CFG_LOG_TOOLS = "qip.ai.trace.log-tools";
  private static final int ARGS_PREVIEW_COMPACT = 200;

  private ToolTraceLog() {}

  public static boolean logToolsVerbose() {
    return ConfigProvider.getConfig()
        .getOptionalValue(CFG_LOG_TOOLS, Boolean.class)
        .orElse(true);
  }

  public static void logToolInvoke(
      Logger log, String toolName, String conversationId, String argsPreview) {
    ToolInvocationSink.onInvoke(toolName);
    String preview = formatArgsPreview(argsPreview);
    if (conversationId != null && !conversationId.isBlank()) {
      log.infof(
          "Tool invoked [%s]: conversationId=%s, argsPreview=%s",
          toolName, conversationId, preview);
    } else {
      log.infof("Tool invoked [%s]: argsPreview=%s", toolName, preview);
    }
  }

  public static void logToolComplete(
      Logger log, String toolName, String conversationId, long durationMs, String resultPreview) {
    ToolInvocationSink.onComplete(toolName);
    String preview = AiTraceLog.preview(resultPreview, AiTraceLog.DEFAULT_TOOL_RESULT_CHARS);
    if (conversationId != null && !conversationId.isBlank()) {
      if (durationMs >= 0) {
        log.infof(
            "Tool completed [%s]: conversationId=%s, durationMs=%d, resultPreview=%s",
            toolName, conversationId, durationMs, preview);
      } else {
        log.infof(
            "Tool completed [%s]: conversationId=%s, resultPreview=%s",
            toolName, conversationId, preview);
      }
    } else if (durationMs >= 0) {
      log.infof(
          "Tool completed [%s]: durationMs=%d, resultPreview=%s", toolName, durationMs, preview);
    } else {
      log.infof("Tool completed [%s]: resultPreview=%s", toolName, preview);
    }
  }

  public static void logToolFailed(
      Logger log, String toolName, String conversationId, long durationMs, Throwable error) {
    ToolInvocationSink.onFailed(toolName);
    if (conversationId != null && !conversationId.isBlank()) {
      if (durationMs >= 0) {
        log.errorf(
            error,
            "Tool failed [%s]: conversationId=%s, durationMs=%d",
            toolName,
            conversationId,
            durationMs);
      } else {
        log.errorf(error, "Tool failed [%s]: conversationId=%s", toolName, conversationId);
      }
    } else if (durationMs >= 0) {
      log.errorf(error, "Tool failed [%s]: durationMs=%d", toolName, durationMs);
    } else {
      log.errorf(error, "Tool failed [%s]", toolName);
    }
  }

  private static String formatArgsPreview(String argsPreview) {
    int max =
        logToolsVerbose() ? AiTraceLog.DEFAULT_HTTP_BODY_DEBUG_CHARS : ARGS_PREVIEW_COMPACT;
    return AiTraceLog.previewOneLine(argsPreview, max);
  }
}
