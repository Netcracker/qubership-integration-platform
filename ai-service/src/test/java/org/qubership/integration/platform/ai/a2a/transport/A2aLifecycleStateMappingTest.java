package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ImplementationBlockedRecovery;

class A2aLifecycleStateMappingTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("snapshotMappings")
  void mapsFacadeSnapshotStatusToA2aState(
      CreateChainExecutionStatus facadeStatus, A2aTaskState expected) {
    assertEquals(expected, CreateChainA2aStateMapper.fromSnapshotStatus(facadeStatus));
  }

  static Stream<Arguments> snapshotMappings() {
    return Stream.of(
        Arguments.of(CreateChainExecutionStatus.WORKING, A2aTaskState.WORKING),
        Arguments.of(CreateChainExecutionStatus.INPUT_REQUIRED, A2aTaskState.INPUT_REQUIRED),
        Arguments.of(CreateChainExecutionStatus.COMPLETED, A2aTaskState.COMPLETED),
        Arguments.of(CreateChainExecutionStatus.FAILED, A2aTaskState.FAILED));
  }

  @Test
  void waitingForImplementNormalPathStaysWorking() {
    CreateChainExecutionSnapshot snapshot =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.WORKING, 2L, null, "");
    ProjectedTask projected =
        CreateChainA2aStateMapper.project(
            snapshot, List.of(new CreateChainEvent.Progress("Working")));
    assertEquals(A2aTaskState.WORKING, projected.state());
    assertNull(projected.pendingAction());
  }

  @Test
  void waitingEventElevatesStaleWorkingSnapshotToInputRequired() {
    CreateChainPendingAction.Clarify clarify =
        new CreateChainPendingAction.Clarify("need input", List.of("q1"));
    CreateChainExecutionSnapshot staleWorking =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.WORKING, 1L, null, "");
    ProjectedTask projected =
        CreateChainA2aStateMapper.project(
            staleWorking,
            List.of(
                new CreateChainEvent.Progress("token"),
                new CreateChainEvent.Waiting(clarify)));
    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
    assertEquals(clarify, projected.pendingAction());
  }

  @Test
  void waitingEventKeepsInputRequiredAfterLateProgressEvent() {
    CreateChainPendingAction.Clarify clarify =
        new CreateChainPendingAction.Clarify("need input", List.of("q1"));
    CreateChainExecutionSnapshot staleWorking =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.WORKING, 1L, null, "");
    ProjectedTask projected =
        CreateChainA2aStateMapper.project(
            staleWorking,
            List.of(
                new CreateChainEvent.Waiting(clarify),
                new CreateChainEvent.Progress("Working")));
    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
    assertEquals(clarify, projected.pendingAction());
  }

  @Test
  void implementationBlockedMapsToInputRequiredWithRecoveryData() {
    ImplementationBlockedRecovery.ApprovePlanEvidence recovery =
        new ImplementationBlockedRecovery.ApprovePlanEvidence(
            "Approved plan hash is unavailable for automatic implementation.",
            "implementation-plan",
            "hash-1",
            4L);
    ProjectedTask projected =
        CreateChainA2aStateMapper.projectBlocked(
            "task-1",
            recovery,
            new CreateChainExecutionSnapshot(
                "task-1", "run-1", CreateChainExecutionStatus.WORKING, 4L, null, ""));
    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
    assertTrue(projected.pendingAction() instanceof CreateChainPendingAction.Approve);
    assertEquals("approve", projected.pendingActionData().get("action"));
    assertEquals("implementation-plan", projected.pendingActionData().get("artifactType"));
  }

  @Test
  void projectOutcomeAcceptsImplementationBlocked() {
    ApproveCreateChainOutcome outcome =
        new ApproveCreateChainOutcome.ImplementationBlocked(
            new ImplementationBlockedRecovery.ApprovePlanEvidence(
                "blocked", "implementation-plan", "h", 1L));
    ProjectedTask projected =
        CreateChainA2aStateMapper.projectOutcome(
            "task-1",
            outcome,
            new CreateChainExecutionSnapshot(
                "task-1", "run-1", CreateChainExecutionStatus.WORKING, 1L, null, ""));
    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
  }

  @Test
  void stageRetryHaltMapsToInputRequiredWithRetryAction() {
    CreateChainPendingAction.Clarify halt =
        new CreateChainPendingAction.Clarify(
            "The catalog could not find that service.",
            List.of(),
            "stage-retry");
    CreateChainExecutionSnapshot snapshot =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 3L, halt, "");
    ProjectedTask projected =
        CreateChainA2aStateMapper.project(
            snapshot, List.of(new CreateChainEvent.Waiting(halt)));

    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
    assertFalse(projected.terminal());
    assertEquals("retry", projected.pendingActionData().get("action"));
    assertEquals(List.of("retry"), projected.pendingActionData().get("allowedActions"));
    assertTrue(projected.statusText().contains("The catalog could not find that service."));
    assertTrue(projected.statusText().contains("retry"));
    assertFalse(
        projected.statusText().toLowerCase().contains("something went wrong"),
        projected.statusText());
  }

  @Test
  void stageReviseHaltMapsToInputRequiredWithRetryAndRevise() {
    CreateChainPendingAction.Clarify halt =
        new CreateChainPendingAction.Clarify(
            "The brief omitted the scheduler.",
            List.of(),
            "stage-revise");
    CreateChainExecutionSnapshot snapshot =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 3L, halt, "");
    ProjectedTask projected =
        CreateChainA2aStateMapper.project(
            snapshot, List.of(new CreateChainEvent.Waiting(halt)));

    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
    assertFalse(projected.terminal());
    assertEquals(List.of("retry", "revise"), projected.pendingActionData().get("allowedActions"));
    assertTrue(projected.statusText().contains("The brief omitted the scheduler."));
    assertTrue(projected.statusText().contains("retry"));
    assertTrue(projected.statusText().contains("revise"));
  }

  @Test
  void internalFailurePublishesOnlyTheUpstreamStagesBoundToItsCard() {
    CreateChainPendingAction.Clarify halt =
        new CreateChainPendingAction.Clarify(
            "A step inside the service broke.",
            List.of("requirement-analysis", "design-planning"),
            "stage-internal-failure");
    CreateChainExecutionSnapshot snapshot =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 3L, halt, "");

    ProjectedTask projected = CreateChainA2aStateMapper.project(snapshot, List.of());

    assertEquals(A2aTaskState.INPUT_REQUIRED, projected.state());
    assertFalse(projected.terminal());
    assertEquals(
        List.of("requirement-analysis", "design-planning"),
        projected.pendingActionData().get("allowedActions"));
  }

  @Test
  void idsPathClarifyStatusTellsA2aClientToReplyYesOrNo() {
    CreateChainPendingAction.Clarify clarify =
        new CreateChainPendingAction.Clarify(
            "Do you want an integration design document (IDS) for these requirements?",
            List.of(),
            "ids-path-choice");
    CreateChainExecutionSnapshot snapshot =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 1L, clarify, "");
    ProjectedTask projected = CreateChainA2aStateMapper.project(snapshot, List.of());
    assertTrue(projected.statusText().contains("Reply \"yes\" or \"no\"."));
  }

  @Test
  void mappingGapClarifyStatusTellsA2aClientToReplyPassThrough() {
    CreateChainPendingAction.Clarify clarify =
        new CreateChainPendingAction.Clarify(
            "Some data mappings are still missing before design can continue.",
            List.of("INITIALIZATION: HTTP GET /health → HTTP GET /status"),
            "mapping-gap");
    CreateChainExecutionSnapshot snapshot =
        new CreateChainExecutionSnapshot(
            "task-1", "run-1", CreateChainExecutionStatus.INPUT_REQUIRED, 1L, clarify, "");
    ProjectedTask projected = CreateChainA2aStateMapper.project(snapshot, List.of());
    assertTrue(projected.statusText().contains("PASS_THROUGH"));
    assertTrue(projected.statusText().contains("INITIALIZATION: HTTP GET /health"));
  }
}
