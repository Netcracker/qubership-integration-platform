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

@Path("/api/ui/v1/chat")
public class UiChatAdapterResource {

  private final ChatExecutionService chatExecutionService;

  @Inject
  UiChatAdapterResource(ChatExecutionService chatExecutionService) {
    this.chatExecutionService = chatExecutionService;
  }

  @POST
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  public Multi<String> chat(ChatRequest request) {
    return chatExecutionService.streamUiDataLines(request);
  }
}
