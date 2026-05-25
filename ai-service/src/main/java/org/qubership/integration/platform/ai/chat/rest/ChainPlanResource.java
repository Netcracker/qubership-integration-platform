package org.qubership.integration.platform.ai.chat.rest;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.chat.chainplan.ChainPlanStatus;

import java.util.Map;
import java.util.Optional;

/** Read-only access to the in-memory active chain implementation plan for a chat conversation. */
@Path("/api/v1/chat/{conversationId}/chain-plan")
@Produces(MediaType.APPLICATION_JSON)
public class ChainPlanResource {

  private static final Logger LOG = Logger.getLogger(ChainPlanResource.class);

  @Inject ActiveChainPlanService activeChainPlanService;

  /**
   * GET /api/v1/chat/{conversationId}/chain-plan — full plan snapshot and open items.
   *
   * @return 404 when there is no active plan for the conversation
   */
  @GET
  @Blocking
  public Response getChainPlan(@PathParam("conversationId") String conversationId) {
    Optional<ActiveChainPlanSnapshot> opt = activeChainPlanService.getActive(conversationId);
    if (opt.isEmpty()) {
      LOG.debugf("chain-plan GET: no active plan conversationId=%s", conversationId);
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "No active chain plan for this conversation"))
          .build();
    }
    ActiveChainPlanSnapshot snap = opt.get();
    ChainPlanResponse body =
        new ChainPlanResponse(
            snap.planId(),
            activeChainPlanService.isApproved(conversationId),
            snap.chainName(),
            snap.apiHubRequired(),
            snap.apiHubReason(),
            snap.plan(),
            snap.openItems());
    return Response.ok(body).build();
  }

  /** GET /api/v1/chat/{conversationId}/chain-plan/status — lightweight status for UI badges. */
  @GET
  @Path("/status")
  @Blocking
  public ChainPlanStatus getChainPlanStatus(@PathParam("conversationId") String conversationId) {
    return activeChainPlanService.getPlanStatus(conversationId);
  }

  /**
   * POST /api/v1/chat/{conversationId}/chain-plan/dismiss-open-items — mark all open plan items
   * user-dismissed.
   */
  @POST
  @Path("/dismiss-open-items")
  @Blocking
  public Response dismissOpenItems(@PathParam("conversationId") String conversationId) {
    if (activeChainPlanService.getActive(conversationId).isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "No active chain plan for this conversation"))
          .build();
    }
    activeChainPlanService.markAllOpenPlanItemsDismissedByUser(conversationId);
    LOG.infof("chain-plan dismiss-open-items: conversationId=%s", conversationId);
    return Response.ok(activeChainPlanService.getPlanStatus(conversationId)).build();
  }

  /**
   * POST /api/v1/chat/{conversationId}/chain-plan/approve — approve for UI Build (skips Gate 2
   * HITL).
   */
  @POST
  @Path("/approve")
  @Blocking
  public Response approveForBuild(@PathParam("conversationId") String conversationId) {
    if (!activeChainPlanService.approvePlanForBuild(conversationId)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "No active chain plan for this conversation"))
          .build();
    }
    LOG.infof("chain-plan approve-for-build: conversationId=%s", conversationId);
    return Response.ok(activeChainPlanService.getPlanStatus(conversationId)).build();
  }
}
