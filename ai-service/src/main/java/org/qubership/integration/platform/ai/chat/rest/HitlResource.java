package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.hitl.HitlCheckpointAnswer;
import org.qubership.integration.platform.ai.chat.hitl.HitlService;

/**
 * REST endpoint for submitting Human-in-the-Loop checkpoint answers.
 *
 * <p>After the frontend receives a {@code hitl_checkpoint} SSE event, it displays the question and
 * options to the user, then POSTs the answer here to resume the AI workflow.
 */
@Path("/api/v1/chat/{conversationId}/checkpoint")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HitlResource {

  private static final Logger LOG = Logger.getLogger(HitlResource.class);

  @Inject HitlService hitlService;

  /**
   * POST /api/v1/chat/{conversationId}/checkpoint/{checkpointId}
   *
   * <p>Submits the user's answer to a HITL checkpoint and resumes the waiting AI workflow.
   *
   * @param conversationId the conversation ID (for logging/audit)
   * @param checkpointId the checkpoint ID from the SSE event payload
   * @param answer the user's answer
   * @return 200 OK if resolved, 404 if checkpoint not found or already expired
   */
  @POST
  @Path("/{checkpointId}")
  @Blocking
  public Response submitAnswer(
      @PathParam("conversationId") String conversationId,
      @PathParam("checkpointId") String checkpointId,
      @Valid HitlCheckpointAnswer answer) {

    LOG.infof(
        "HITL answer received: conversationId=%s, checkpointId=%s, answer=%s",
        conversationId, checkpointId, answer.getAnswer());

    boolean resolved = hitlService.resolveCheckpoint(checkpointId, answer);

    if (!resolved) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity("{\"error\":\"Checkpoint not found or already expired: " + checkpointId + "\"}")
          .build();
    }

    return Response.ok("{\"status\":\"resolved\",\"checkpointId\":\"" + checkpointId + "\"}")
        .build();
  }
}
