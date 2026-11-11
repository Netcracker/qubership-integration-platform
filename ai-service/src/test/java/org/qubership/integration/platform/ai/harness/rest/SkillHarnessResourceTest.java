package org.qubership.integration.platform.ai.harness.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessRequest;
import org.qubership.integration.platform.ai.harness.ChainPatchRefusal;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessResponse;
import org.qubership.integration.platform.ai.harness.ChainPatchHarnessService;
import org.qubership.integration.platform.ai.harness.ExecutorHarnessRequest;
import org.qubership.integration.platform.ai.harness.ExecutorHarnessResponse;
import org.qubership.integration.platform.ai.harness.ExecutorHarnessService;
import org.qubership.integration.platform.ai.harness.PlannerHarnessRequest;
import org.qubership.integration.platform.ai.harness.PlannerHarnessResponse;
import org.qubership.integration.platform.ai.harness.PlannerHarnessService;
import org.qubership.integration.platform.ai.harness.SkillHarnessRequest;
import org.qubership.integration.platform.ai.harness.SkillHarnessResponse;
import org.qubership.integration.platform.ai.harness.SkillHarnessService;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;
import org.qubership.integration.platform.ai.harness.SkillHarnessStatus;

class SkillHarnessResourceTest {

  private static final String CHAIN_ID = "chain-harness-1";
  private static final String SKILL_ID = "cip-trigger-generator";
  private static final String PROMPT = "Configure the HTTP trigger on this chain.";
  private static final String CONVERSATION_ID = "conv-harness-1";
  private static final String AUTH = "Bearer test-harness-caller";

  private SkillHarnessService harnessService;
  private ChainPatchHarnessService chainPatchHarnessService;
  private PlannerHarnessService plannerHarnessService;
  private ExecutorHarnessService executorHarnessService;
  private SkillHarnessResource resource;

  @BeforeEach
  void setUp() {
    harnessService = mock(SkillHarnessService.class);
    chainPatchHarnessService = mock(ChainPatchHarnessService.class);
    plannerHarnessService = mock(PlannerHarnessService.class);
    executorHarnessService = mock(ExecutorHarnessService.class);
    resource =
        new SkillHarnessResource(
            harnessService,
            chainPatchHarnessService,
            plannerHarnessService,
            executorHarnessService,
            true);
  }

  @Test
  void disabledHarnessRejectsEveryRouteBeforeRunningServices() {
    resource = new SkillHarnessResource(
        harnessService, chainPatchHarnessService, plannerHarnessService, executorHarnessService, false);

    assertThrows(jakarta.ws.rs.NotFoundException.class, () -> resource.run(AUTH, null));
    assertThrows(jakarta.ws.rs.NotFoundException.class, () -> resource.runPlanner(AUTH, null));
    assertThrows(jakarta.ws.rs.NotFoundException.class, () -> resource.runExecutor(AUTH, null));
    assertThrows(jakarta.ws.rs.NotFoundException.class, () -> resource.runChainPatch(AUTH, null));
    verifyNoInteractions(harnessService, chainPatchHarnessService, plannerHarnessService, executorHarnessService);
  }

  @Test
  void runExecutorReturnsRecordedEvidence() {
    ExecutorHarnessResponse expected =
        new ExecutorHarnessResponse(
            CONVERSATION_ID,
            SkillHarnessStatus.COMPLETED,
            "compiled",
            false,
            "test-model",
            List.of("cip-trigger-generator"),
            List.of("cip-trigger-generator"),
            List.of(),
            null,
            null,
            null,
            null,
            List.of(),
            java.util.Set.of(),
            10);
    when(executorHarnessService.run(any())).thenReturn(expected);

    Response response =
        resource.runExecutor(
        AUTH,
            new ExecutorHarnessRequest(
                CONVERSATION_ID,
                "1. Generate trigger (cip-trigger-generator)",
                org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures
                    .linearOrders(),
                new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                    "orders", List.of(), List.of(), List.of(), List.of(), "orders"),
                List.of()));

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(expected, response.getEntity());
    verify(executorHarnessService).run(any());
  }

  @Test
  void runExecutorRejectsMissingSavedPlannerResponse() {
    Response response =
        resource.runExecutor(AUTH, new ExecutorHarnessRequest(CONVERSATION_ID, " ", null, null, List.of()));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("plannerResponse"));
    verifyNoInteractions(executorHarnessService);
  }

  @Test
  void runPlannerReturnsRecordedAttempts() {
    PlannerHarnessResponse expected =
        new PlannerHarnessResponse(
            CONVERSATION_ID,
            SkillHarnessStatus.COMPLETED,
            "plan",
            "skill-hash",
            "test-model",
            List.of(
                new PlannerHarnessResponse.Attempt(1, null, "plan", null, 10)));
    when(plannerHarnessService.run(any())).thenReturn(expected);

    Response response =
        resource.runPlanner(AUTH, new PlannerHarnessRequest(CONVERSATION_ID, "IDS", null));

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertEquals(expected, response.getEntity());
    verify(plannerHarnessService).run(any());
  }

  @Test
  void runPlannerRejectsMissingInput() {
    Response response =
        resource.runPlanner(AUTH, new PlannerHarnessRequest(CONVERSATION_ID, " ", null));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("input"));
    verifyNoInteractions(plannerHarnessService);
  }

  @Test
  void runReturnsCompletedResponseWithChainAndSkillIds() {
    when(harnessService.run(any()))
        .thenReturn(
            new SkillHarnessResponse(
                CONVERSATION_ID, SkillHarnessStatus.COMPLETED, "Trigger configured"));

    Response response =
        resource.run(
        AUTH,
            new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, SKILL_ID, PROMPT));

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    SkillHarnessResponse body = (SkillHarnessResponse) response.getEntity();
    assertEquals(CONVERSATION_ID, body.conversationId());
    assertEquals(SkillHarnessStatus.COMPLETED, body.status());

    ArgumentCaptor<SkillHarnessRequest> captor = ArgumentCaptor.forClass(SkillHarnessRequest.class);
    verify(harnessService).run(captor.capture());
    assertEquals(CHAIN_ID, captor.getValue().chainId());
    assertEquals(SKILL_ID, captor.getValue().skillId());
    assertEquals(PROMPT, captor.getValue().prompt());
  }

  @Test
  void runRejectsMissingChainId() {
    Response response =
        resource.run(AUTH, new SkillHarnessRequest(CONVERSATION_ID, "  ", SKILL_ID, PROMPT));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("chainId"));
    verifyNoInteractions(harnessService);
  }

  @Test
  void runRejectsMissingSkillId() {
    Response response =
        resource.run(AUTH, new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, "", PROMPT));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("skillId"));
    verifyNoInteractions(harnessService);
  }

  @Test
  void runRejectsMissingPrompt() {
    Response response =
        resource.run(AUTH, new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, SKILL_ID, null));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("prompt"));
    verifyNoInteractions(harnessService);
  }

  @Test
  void runRejectsNullRequestBody() {
    Response response = resource.run(AUTH, null);

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    verifyNoInteractions(harnessService);
  }

  @Test
  void runChainPatchReturnsCompletedResponseWithChangedElementIds() {
    when(chainPatchHarnessService.run(any()))
        .thenReturn(
            new ChainPatchHarnessResponse(
                CONVERSATION_ID,
                SkillHarnessStatus.COMPLETED,
                "Changed 1 element(s).",
                ChainPatchRefusal.NONE,
                List.of("element-script"),
                List.of()));

    Response response =
        resource.runChainPatch(
        AUTH,
            new ChainPatchHarnessRequest(CONVERSATION_ID, CHAIN_ID, "fix the script"));

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ChainPatchHarnessResponse body = (ChainPatchHarnessResponse) response.getEntity();
    assertEquals(SkillHarnessStatus.COMPLETED, body.status());
    assertEquals(List.of("element-script"), body.changedElementIds());

    ArgumentCaptor<ChainPatchHarnessRequest> captor =
        ArgumentCaptor.forClass(ChainPatchHarnessRequest.class);
    verify(chainPatchHarnessService).run(captor.capture());
    assertEquals(CHAIN_ID, captor.getValue().chainId());
  }

  @Test
  void runChainPatchRejectsMissingChainId() {
    Response response =
        resource.runChainPatch(AUTH, new ChainPatchHarnessRequest(CONVERSATION_ID, " ", "fix it"));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("chainId"));
    verifyNoInteractions(chainPatchHarnessService);
  }

  @Test
  void runChainPatchRejectsMissingPrompt() {
    Response response =
        resource.runChainPatch(AUTH, new ChainPatchHarnessRequest(CONVERSATION_ID, CHAIN_ID, null));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("prompt"));
    verifyNoInteractions(chainPatchHarnessService);
  }

  @Test
  void runChainPatchRejectsNullRequestBody() {
    Response response = resource.runChainPatch(AUTH, null);

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    verifyNoInteractions(chainPatchHarnessService);
  }

  @Test
  void withAuthRequiredAHarnessRunWithoutABearerIsRejected() {
    assertThrows(
        NotAuthorizedException.class,
        () -> resource.run(null, new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, SKILL_ID, PROMPT)));
    verifyNoInteractions(harnessService);
  }

  @Test
  void skillRunBindsTheCallersBearerForItsConversation() {
    java.util.concurrent.atomic.AtomicReference<String> seenToken =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(harnessService.run(any()))
        .thenAnswer(
            invocation -> {
              seenToken.set(UserTokenStore.current().orElse(null));
              assertEquals(CONVERSATION_ID, ToolSession.resolveConversationId());
              return new SkillHarnessResponse(
                  CONVERSATION_ID, SkillHarnessStatus.COMPLETED, "Trigger configured");
            });

    resource.run(
        AUTH, new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, SKILL_ID, PROMPT));

    assertEquals("test-harness-caller", seenToken.get());
  }
}
