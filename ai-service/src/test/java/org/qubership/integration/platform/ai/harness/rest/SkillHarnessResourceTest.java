package org.qubership.integration.platform.ai.harness.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.harness.SkillHarnessRequest;
import org.qubership.integration.platform.ai.harness.SkillHarnessResponse;
import org.qubership.integration.platform.ai.harness.SkillHarnessService;
import org.qubership.integration.platform.ai.harness.SkillHarnessStatus;

class SkillHarnessResourceTest {

  private static final String CHAIN_ID = "chain-harness-1";
  private static final String SKILL_ID = "cip-trigger-generator";
  private static final String PROMPT = "Configure the HTTP trigger on this chain.";
  private static final String CONVERSATION_ID = "conv-harness-1";

  private SkillHarnessService harnessService;
  private SkillHarnessResource resource;

  @BeforeEach
  void setUp() {
    harnessService = mock(SkillHarnessService.class);
    resource = new SkillHarnessResource(harnessService);
  }

  @Test
  void runReturnsCompletedResponseWithChainAndSkillIds() {
    when(harnessService.run(any()))
        .thenReturn(
            new SkillHarnessResponse(
                CONVERSATION_ID, SkillHarnessStatus.COMPLETED, "Trigger configured"));

    Response response =
        resource.run(
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
        resource.run(new SkillHarnessRequest(CONVERSATION_ID, "  ", SKILL_ID, PROMPT));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("chainId"));
    verifyNoInteractions(harnessService);
  }

  @Test
  void runRejectsMissingSkillId() {
    Response response =
        resource.run(new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, "", PROMPT));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("skillId"));
    verifyNoInteractions(harnessService);
  }

  @Test
  void runRejectsMissingPrompt() {
    Response response =
        resource.run(new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, SKILL_ID, null));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("prompt"));
    verifyNoInteractions(harnessService);
  }

  @Test
  void runRejectsNullRequestBody() {
    Response response = resource.run(null);

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    verifyNoInteractions(harnessService);
  }
}
