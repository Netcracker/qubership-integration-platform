package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ConversationTurnReset;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.TruncateRequest;

/**
 * Edit/Regenerate/Clear turn reset endpoints. Truncate uses contract A: callers pass {@code
 * afterMessageIndex = serverUserIndex - 1}, then {@code POST /api/v1/chat} re-adds the user message
 * via {@code streamSse}.
 */
@Path("/api/v1/chat/conversations/{conversationId}")
public class ConversationTurnResource {

  private static final Logger LOG = Logger.getLogger(ConversationTurnResource.class);

  private final ConversationService conversationService;
  private final ConversationTurnReset turnReset;

  @Inject
  ConversationTurnResource(
      ConversationService conversationService, ConversationTurnReset turnReset) {
    this.conversationService = conversationService;
    this.turnReset = turnReset;
  }

  @POST
  @Path("/truncate")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response truncate(
      @PathParam("conversationId") String conversationId, TruncateRequest body) {
    if (body == null) {
      return badRequest("Request body is required");
    }
    int afterMessageIndex = body.afterMessageIndex();
    Response validationError = validateAfterMessageIndex(conversationId, afterMessageIndex);
    if (validationError != null) {
      return validationError;
    }

    turnReset.truncateAndReset(conversationId, afterMessageIndex);
    LOG.debugf(
        "conversation truncate: conversationId=%s afterMessageIndex=%d",
        conversationId, afterMessageIndex);
    return Response.noContent().build();
  }

  @POST
  @Path("/reset")
  @Blocking
  public Response reset(@PathParam("conversationId") String conversationId) {
    turnReset.fullReset(conversationId);
    LOG.debugf("conversation reset: conversationId=%s", conversationId);
    return Response.noContent().build();
  }

  private Response validateAfterMessageIndex(String conversationId, int afterMessageIndex) {
    if (afterMessageIndex < -1) {
      return badRequest("afterMessageIndex must be -1 or greater");
    }
    int messageCount = conversationService.getMessages(conversationId).size();
    if (afterMessageIndex >= messageCount) {
      return badRequest(
          "afterMessageIndex "
              + afterMessageIndex
              + " is past the end of the conversation (size "
              + messageCount
              + ")");
    }
    return null;
  }

  private static Response badRequest(String message) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Map.of("error", message))
        .build();
  }
}
