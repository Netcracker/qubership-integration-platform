package org.qubership.integration.platform.ai.productpipeline.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;

/**
 * Proves the pipeline-neutral view reports what the create-chain view reports, so chat code reading
 * through {@link PendingAction} sees the binding A2A sees.
 */
class NeutralFacadeViewTest {

  private static final String CONVERSATION = "conv-1";

  @Test
  void neutralViewReportsTheSameApprovalBindingAsTheCreateChainView() {
    CreateChainPendingAction.Approve createChainView =
        new CreateChainPendingAction.Approve(
            "implementation-plan", "sha256:abc", 7L, "Approve the plan?");
    CreateChainExecutionSnapshot snapshot = snapshotOf(createChainView);

    ExecutionSnapshot neutralSnapshot = snapshot;
    PendingAction.Approve neutralView =
        assertInstanceOf(PendingAction.Approve.class, neutralSnapshot.pendingAction());

    assertEquals(createChainView.artifactType(), neutralView.artifactType());
    assertEquals(createChainView.artifactHash(), neutralView.artifactHash());
    assertEquals(createChainView.revision(), neutralView.revision());
    assertEquals(createChainView.prompt(), neutralView.prompt());
    assertEquals("approve", neutralView.action());
    assertEquals(snapshot.taskId(), neutralSnapshot.taskId());
    assertEquals(snapshot.runId(), neutralSnapshot.runId());
    assertEquals(snapshot.revision(), neutralSnapshot.revision());
  }

  @Test
  void neutralViewReportsTheSameClarificationAsTheCreateChainView() {
    CreateChainPendingAction.Clarify createChainView =
        new CreateChainPendingAction.Clarify("Missing evidence", List.of("target system"));

    PendingAction.Clarify neutralView =
        assertInstanceOf(PendingAction.Clarify.class, snapshotOf(createChainView).pendingAction());

    assertEquals(createChainView.reason(), neutralView.reason());
    assertEquals(createChainView.missingEvidence(), neutralView.missingEvidence());
    assertEquals("clarify", neutralView.action());
  }

  @Test
  void queryResolvesAConversationToItsOpenWait() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot(CONVERSATION))
        .thenReturn(
            Optional.of(
                snapshotOf(
                    new CreateChainPendingAction.Approve(
                        "requirement-brief", "sha256:def", 3L, "Approve the brief?"))));

    PendingAction.Approve pending =
        assertInstanceOf(
            PendingAction.Approve.class,
            new PendingActionQuery(facade).forConversation(CONVERSATION).orElseThrow());

    assertEquals("requirement-brief", pending.artifactType());
    assertEquals("sha256:def", pending.artifactHash());
    assertEquals(3L, pending.revision());
  }

  @Test
  void queryReportsNothingWhenTheRunWaitsForNothing() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot(CONVERSATION)).thenReturn(Optional.of(snapshotOf(null)));

    assertTrue(new PendingActionQuery(facade).forConversation(CONVERSATION).isEmpty());
  }

  @Test
  void queryReportsNothingWhenTheConversationHasNoRun() {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.snapshot(CONVERSATION)).thenReturn(Optional.empty());

    assertTrue(new PendingActionQuery(facade).forConversation(CONVERSATION).isEmpty());
  }

  private static CreateChainExecutionSnapshot snapshotOf(CreateChainPendingAction pendingAction) {
    return new CreateChainExecutionSnapshot(
        CONVERSATION,
        "run-1",
        pendingAction == null
            ? CreateChainExecutionStatus.WORKING
            : CreateChainExecutionStatus.INPUT_REQUIRED,
        pendingAction instanceof CreateChainPendingAction.Approve approve ? approve.revision() : 1L,
        pendingAction,
        "");
  }
}
