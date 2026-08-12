package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;

class ChatDecisionServiceTest {

  @Test
  void markerNamesTheApprovedArtifactInEnglish() {
    assertEquals(
        "Approved implementation-plan sha256:abc",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.APPROVE_ACTION, "implementation-plan", "sha256:abc", null)));
  }

  @Test
  void markerKeepsTheReadersComment() {
    String marker =
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.APPROVE_ACTION, "requirement-brief", "sha256:def", "keep the timeout"));

    assertTrue(marker.startsWith("Approved requirement-brief sha256:def"), marker);
    assertTrue(marker.endsWith("keep the timeout"), marker);
  }

  @Test
  void markerNamesAChangeRequestWithoutTheHash() {
    assertEquals(
        "Requested changes to implementation-plan",
        ChatDecisionService.transcriptMarker(
            command(ChatEvent.REQUEST_CHANGES_ACTION, "implementation-plan", "sha256:abc", null)));
  }

  @Test
  void changeRequestRunsNoCommand() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);

    assertTrue(
        new ChatDecisionService(facade)
            .apply(
                "conv-1",
                command(ChatEvent.REQUEST_CHANGES_ACTION, "implementation-plan", "sha256:abc", null))
            .collect()
            .asList()
            .await()
            .indefinitely()
            .isEmpty());
    verify(facade, never()).streamApprove(any(ApproveCreateChainArtifactCommand.class));
  }

  private static ChatDecisionCommand command(
      String action, String artifactType, String artifactHash, String comment) {
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactType(artifactType);
    command.setArtifactHash(artifactHash);
    command.setRevision(3L);
    command.setComment(comment);
    return command;
  }
}
