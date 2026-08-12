package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.ApprovalQuestionStore;

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
        new ChatDecisionService(facade, questionStore())
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

  @Test
  void openDecisionFillsInTheStoredQuestionWhenTheWaitCarriesNone() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    5L,
                    new CreateChainPendingAction.Approve(
                        "implementation-plan", "sha256:abc", 5L, ""),
                    "")));
    ApprovalQuestionStore questions = questionStore();
    questions.save("conv-1", "sha256:abc", "Approve the plan?");

    ChatEvent.Decision decision =
        new ChatDecisionService(facade, questions).openDecision("conv-1").orElseThrow();

    assertEquals("approve:sha256:abc", decision.id());
    assertEquals("Approve the plan?", decision.question());
    assertEquals("sha256:abc", decision.artifactHash());
    assertEquals(5L, decision.revision());
  }

  @Test
  void openDecisionReportsNothingWhenTheRunWaitsForNothing() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot("conv-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "conv-1", "run-1", CreateChainExecutionStatus.WORKING, 5L, null, "")));

    assertTrue(
        new ChatDecisionService(facade, questionStore()).openDecision("conv-1").isEmpty());
  }

  private static ApprovalQuestionStore questionStore() {
    return new ApprovalQuestionStore(new InMemoryArtifactBlobStore());
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
