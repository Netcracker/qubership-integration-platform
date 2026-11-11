package org.qubership.integration.platform.ai.harness.rest;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessRequest;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessResponse;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessService;
import org.qubership.integration.platform.ai.harness.ExecutorHarnessRequest;
import org.qubership.integration.platform.ai.harness.ExecutorHarnessResponse;
import org.qubership.integration.platform.ai.harness.ExecutorHarnessService;
import org.qubership.integration.platform.ai.harness.PlannerHarnessRequest;
import org.qubership.integration.platform.ai.harness.PlannerHarnessResponse;
import org.qubership.integration.platform.ai.harness.PlannerHarnessService;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;
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
  private final ExecutorHarnessService executorHarnessService;
  private final boolean enabled;

  @Inject
  public SkillHarnessResource(
      SkillHarnessService harnessService,
      ChainPatchHarnessService chainPatchHarnessService,
      PlannerHarnessService plannerHarnessService,
      ExecutorHarnessService executorHarnessService,
      @ConfigProperty(name = "qip.ai.harness.enabled", defaultValue = "false") boolean enabled) {
    this.harnessService = harnessService;
    this.chainPatchHarnessService = chainPatchHarnessService;
    this.plannerHarnessService = plannerHarnessService;
    this.executorHarnessService = executorHarnessService;
    this.enabled = enabled;
  }

  @POST
  @Path("/executor-run")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response runExecutor(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      ExecutorHarnessRequest request) {
    requireEnabled();
    if (request == null) {
      return badRequest("Request body is required");
    }
    if (request.designPlanContract() == null && isBlank(request.plannerResponse())) {
      return badRequest("plannerResponse or designPlanContract is required");
    }
    if (request.designPlanContract() != null && request.designPlanReport() == null) {
      return badRequest("designPlanReport is required with designPlanContract");
    }
    if (request.semanticRevision() == null) {
      return badRequest("semanticRevision is required");
    }
    if (request.requirementBrief() == null) {
      return badRequest("requirementBrief is required");
    }

    String conversationId = resolveConversationId(request.conversationId());
    ExecutorHarnessRequest bound =
        new ExecutorHarnessRequest(
            conversationId,
            request.plannerResponse(),
            request.semanticRevision(),
            request.requirementBrief(),
            request.bindings(),
            request.designPlanContract(),
            request.designPlanReport());
    return withTurnBearer(
        authorization, conversationId, () -> Response.ok(executorHarnessService.run(bound)).build());
  }

  @POST
  @Path("/planner-run")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response runPlanner(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      PlannerHarnessRequest request) {
    requireEnabled();
    if (request == null) {
      return badRequest("Request body is required");
    }
    if (isBlank(request.input())) {
      return badRequest("input is required");
    }

    String conversationId = resolveConversationId(request.conversationId());
    PlannerHarnessRequest bound =
        new PlannerHarnessRequest(conversationId, request.input(), request.repairEvidenceText());
    return withTurnBearer(
        authorization, conversationId, () -> Response.ok(plannerHarnessService.run(bound)).build());
  }

  @POST
  @Path("/skill-run")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response run(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, SkillHarnessRequest request) {
    requireEnabled();
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

    String conversationId = resolveConversationId(request.conversationId());
    SkillHarnessRequest bound =
        new SkillHarnessRequest(
            conversationId, request.chainId(), request.skillId(), request.prompt());
    return withTurnBearer(
        authorization, conversationId, () -> Response.ok(harnessService.run(bound)).build());
  }

  @POST
  @Path("/chain-patch-run")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response runChainPatch(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      ChainPatchHarnessRequest request) {
    requireEnabled();
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

    String conversationId = resolveConversationId(request.conversationId());
    ChainPatchHarnessRequest bound =
        new ChainPatchHarnessRequest(
            conversationId, request.chainId(), request.prompt(), request.allowRemoval());
    return withTurnBearer(
        authorization,
        conversationId,
        () -> Response.ok(chainPatchHarnessService.run(bound)).build());
  }

  /**
   * Binds the caller's bearer to the harness run's synthetic conversation, so its catalog calls
   * carry that bearer rather than the service identity. With auth required a missing bearer is
   * rejected before the run starts.
   */
  private static Response withTurnBearer(
      String authorization, String conversationId, java.util.function.Supplier<Response> run) {
    String token = UserTokenStore.bearerForTurn(authorization);
    if (token == null) {
      return run.get();
    }
    UserTokenStore.put(conversationId, token);
    try (ToolSession.Handle ignored = ToolSession.open(conversationId)) {
      return run.get();
    } finally {
      UserTokenStore.remove(conversationId, token);
    }
  }

  private static String resolveConversationId(String conversationId) {
    return conversationId == null || conversationId.isBlank()
        ? UUID.randomUUID().toString()
        : conversationId.trim();
  }

  private void requireEnabled() {
    if (!enabled) {
      throw new NotFoundException();
    }
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
