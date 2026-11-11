package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.chat.service.ChatExecutionService;

@Path("/api/v1/chat")
public class ChatController {

  private final ChatExecutionService chatExecutionService;
  private final ChatDecisionService chatDecisionService;

  @Inject
  ChatController(
      ChatExecutionService chatExecutionService, ChatDecisionService chatDecisionService) {
    this.chatExecutionService = chatExecutionService;
    this.chatDecisionService = chatDecisionService;
  }

  @POST
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  public Multi<String> chat(ChatRequest request) {
    return chatExecutionService.streamV1Sse(request);
  }

  /** The gate this conversation is stopped at, or 204 when it waits for nothing. */
  @GET
  @Blocking
  @Path("/{conversationId}/decision")
  @Produces(MediaType.APPLICATION_JSON)
  public ChatEvent.Decision openDecision(@PathParam("conversationId") String conversationId) {
    return chatDecisionService.openDecision(conversationId).orElse(null);
  }
}
