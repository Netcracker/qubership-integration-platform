package org.qubership.integration.platform.ai.harness.rest;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessRequest;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessResponse;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessService;
import org.qubership.integration.platform.ai.harness.PlannerHarnessRequest;
import org.qubership.integration.platform.ai.harness.PlannerHarnessResponse;
import org.qubership.integration.platform.ai.harness.PlannerHarnessService;
import org.qubership.integration.platform.ai.harness.SkillHarnessRequest;
import org.qubership.integration.platform.ai.harness.SkillHarnessResponse;
import org.qubership.integration.platform.ai.harness.SkillHarnessService;

/** REST entry point for isolated skill, planner, and chain-patch harness runs. */
@Path("/api/v1/harness")
@Produces(MediaType.APPLICATION_JSON)
public class SkillHarnessResource {

  private static final Logger LOG = Logger.getLogger(SkillHarnessResource.class);

  private final SkillHarnessService harnessService;
  private final ChainPatchHarnessService chainPatchHarnessService;
  private final PlannerHarnessService plannerHarnessService;

  @Inject
  public SkillHarnessResource(
      SkillHarnessService harnessService,
      ChainPatchHarnessService chainPatchHarnessService,
      PlannerHarnessService plannerHarnessService) {
    this.harnessService = harnessService;
    this.chainPatchHarnessService = chainPatchHarnessService;
    this.plannerHarnessService = plannerHarnessService;
  }

  @POST
  @Path("/planner-run")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response runPlanner(PlannerHarnessRequest request) {
    if (request == null) {
      return badRequest("Request body is required");
    }
    if (isBlank(request.input())) {
      return badRequest("input is required");
    }

    PlannerHarnessResponse response = plannerHarnessService.run(request);
    return Response.ok(response).build();
  }

  @POST
  @Path("/skill-run")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response run(SkillHarnessRequest request) {
    if (request == null) {
      return badRequest("Request body is required");
    }
    if (isBlank(request.chainId())) {
      return badRequest("chainId is required");
    }
    if (isBlank(request.skillId())) {
      return badRequest("skillId is required");
    }
    if (isBlank(request.prompt())) {
      return badRequest("prompt is required");
    }

    LOG.infof(
        "skill harness run: conversationId=%s chainId=%s skillId=%s",
        request.conversationId(), request.chainId(), request.skillId());

    SkillHarnessResponse response = harnessService.run(request);
    return Response.ok(response).build();
  }

  @POST
  @Path("/chain-patch-run")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response runChainPatch(ChainPatchHarnessRequest request) {
    if (request == null) {
      return badRequest("Request body is required");
    }
    if (isBlank(request.chainId())) {
      return badRequest("chainId is required");
    }
    if (isBlank(request.prompt())) {
      return badRequest("prompt is required");
    }

    LOG.infof(
        "chain patch harness run: conversationId=%s chainId=%s", request.conversationId(), request.chainId());

    ChainPatchHarnessResponse response = chainPatchHarnessService.run(request);
    return Response.ok(response).build();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static Response badRequest(String message) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Map.of("error", message))
        .build();
  }
}
