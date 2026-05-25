package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.service.ChatExecutionService;

/**
 * Main chat endpoint. Accepts a message and streams the AI response as SSE tokens.
 *
 * <p>SSE event types:
 *
 * <ul>
 *   <li>{@code token} — a single LLM output token
 *   <li>{@code hitl_checkpoint} — a HITL checkpoint payload (JSON); frontend must present this to
 *       the user and POST the answer to {@code /api/v1/chat/{id}/checkpoint/{cpId}}
 *   <li>{@code done} — signals stream completion; payload is the conversationId
 *   <li>{@code error} — signals an error; payload is an error message
 * </ul>
 */
@Path("/api/v1/chat")
public class ChatController {

  private static final Logger LOG = Logger.getLogger(ChatController.class);

  @Inject ChatExecutionService chatExecutionService;

  /**
   * POST /api/v1/chat
   *
   * <p>Accepts a chat request and returns a streaming SSE response. Each SSE event is formatted as
   * {@code event: <type>\ndata: <payload>\n\n}.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Blocking
  public Multi<String> chat(@Valid ChatRequest request) {
    LOG.infof(
        "HTTP POST /api/v1/chat (delegating to ChatExecutionService.streamV1Sse); scenarioHint=%s",
        request.getScenarioHint());
    return chatExecutionService.streamV1Sse(request);
  }

  /** GET /api/v1/chat/{conversationId}/history Returns the conversation message history. */
  @GET
  @Path("/{conversationId}/history")
  @Produces(MediaType.APPLICATION_JSON)
  @Blocking
  public jakarta.ws.rs.core.Response getHistory(
      @PathParam("conversationId") String conversationId) {
    return chatExecutionService.getHistoryResponse(conversationId);
  }
}
