package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.service.ChatExecutionService;

@Path("/api/v1/chat")
public class ChatController {

  private final ChatExecutionService chatExecutionService;

  @Inject
  ChatController(ChatExecutionService chatExecutionService) {
    this.chatExecutionService = chatExecutionService;
  }

  @POST
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  public Multi<String> chat(ChatRequest request) {
    return chatExecutionService.streamV1Sse(request);
  }
}
