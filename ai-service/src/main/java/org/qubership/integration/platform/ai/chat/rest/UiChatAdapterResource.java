package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.qubership.integration.platform.ai.chat.mapper.UiWithProgressToChatRequestMapper;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.model.UiWithProgressRequest;
import org.qubership.integration.platform.ai.chat.service.ChatExecutionService;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

/**
 * HTTP contract for streaming chat compatible with {@code event: token|done|error|hitl_checkpoint}
 * SSE payloads. Translates the UI request into {@link ChatRequest} and delegates to {@link
 * ChatExecutionService#streamUiDataLines}.
 */
@Path("/api/chat")
public class UiChatAdapterResource {

  private static final Logger LOG = Logger.getLogger(UiChatAdapterResource.class);

  @Inject ChatExecutionService chatExecutionService;

  @POST
  @Path("with-progress")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Blocking
  public Multi<String> withProgress(UiWithProgressRequest body) {
    if (body == null) {
      throw new BadRequestException("request body is required");
    }
    ChatRequest internal = UiWithProgressToChatRequestMapper.toInternal(body);
    if (internal == null) {
      throw new BadRequestException(
          "No user message: provide messages with a non-empty last user role entry");
    }
    LOG.infof(
        "HTTP POST /api/chat/with-progress: conversationId=%s, scenarioHint=%s, userPreview=%s",
        internal.getConversationId() != null ? internal.getConversationId() : "(assign-new)",
        internal.getScenarioHint(),
        AiTraceLog.previewOneLine(
            internal.getEffectiveUserText(), AiTraceLog.DEFAULT_USER_PREVIEW_CHARS));
    return chatExecutionService.streamUiDataLines(internal);
  }
}
