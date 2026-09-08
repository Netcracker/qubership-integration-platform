package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.service.CatalogAuthorizationBinder;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.chat.service.ChatExecutionService;

@Path("/api/v1/chat")
public class ChatController {

  private final ChatExecutionService chatExecutionService;
  private final ChatDecisionService chatDecisionService;
  private final CatalogAuthorizationBinder catalogAuthorizationBinder;

  @Inject
  ChatController(
      ChatExecutionService chatExecutionService,
      ChatDecisionService chatDecisionService,
      CatalogAuthorizationBinder catalogAuthorizationBinder) {
    this.chatExecutionService = chatExecutionService;
    this.chatDecisionService = chatDecisionService;
    this.catalogAuthorizationBinder = catalogAuthorizationBinder;
  }

  @POST
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  public Multi<String> chat(ChatRequest request) {
    return chatExecutionService.streamV1Sse(request);
  }

  /** Hot-swap catalog authorization for an in-flight conversation. */
  @PUT
  @Path("/{conversationId}/catalog-auth")
  public Response refreshCatalogAuthorization(
      @PathParam("conversationId") String conversationId,
      @jakarta.ws.rs.core.Context HttpHeaders headers) {
    String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
    return switch (catalogAuthorizationBinder.refreshConversation(conversationId, authorization)) {
      case UPDATED -> Response.noContent().build();
      case INVALID -> Response.status(Response.Status.BAD_REQUEST)
          .entity("Authorization header with a Bearer token is required")
          .build();
      case NOT_BOUND -> Response.status(Response.Status.NOT_FOUND).build();
    };
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
