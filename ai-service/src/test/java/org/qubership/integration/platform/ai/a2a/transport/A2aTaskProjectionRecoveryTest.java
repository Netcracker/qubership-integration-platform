package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskTransitionResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskUpdate;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;

class A2aTaskProjectionRecoveryTest {
  private final AtomicReference<A2aPersistedTask> row = new AtomicReference<>();
  private final A2aTaskRepository repository = mock(A2aTaskRepository.class);
  private final CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
  private A2aTaskSnapshotPersister persister;
  private static final CallerContext CALLER = new CallerContext("tenant", "subject");

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    Instance<CreateChainApplicationFacade> provider = mock(Instance.class);
    when(provider.get()).thenReturn(facade);
    persister = new A2aTaskSnapshotPersister(repository, mock(A2aPersistedSnapshotHook.class),
        new ObjectMapper(), provider);
    when(facade.publicState(anyString())).thenReturn(Optional.empty());
    when(facade.snapshot(anyString())).thenReturn(Optional.empty());
    when(repository.findByTaskId("task")).thenAnswer(ignored -> Optional.ofNullable(row.get()));
    when(repository.insert(any())).thenAnswer(invocation -> {
      A2aTaskCreate create = invocation.getArgument(0);
      row.set(saved(create.state(), 1L, create.publicSnapshotJson(), create.messageHistoryJson(),
          create.artifactMetadataJson()));
      return row.get();
    });
    when(repository.transition(anyString(), any())).thenAnswer(invocation -> {
      A2aTaskUpdate update = invocation.getArgument(1);
      assertEquals(row.get().revision(), update.expectedRevision());
      row.set(saved(update.state(), update.expectedRevision() + 1, update.publicSnapshotJson(),
          update.messageHistoryJson(), update.artifactMetadataJson()));
      return new A2aTaskTransitionResult.Applied(row.get());
    });
  }

  @Test
  void readRecoversCompletionAndPreservesHistoryAndEarlierArtifacts() {
    Message source = Message.builder().role(Message.Role.ROLE_USER).messageId("source")
        .parts(new TextPart("Keep customerRef unchanged.")).build();
    persist(snapshot(1, CreateChainExecutionStatus.WORKING, null),
        List.of(new CreateChainEvent.ArtifactReady("requirement-draft", "draft", "draft-hash", 1,
            Map.of("goal", "Keep customerRef unchanged."))), List.of(source));
    var complete = snapshot(8, CreateChainExecutionStatus.COMPLETED, null);
    when(facade.publicState("task")).thenReturn(Optional.of(new CreateChainApplicationFacade.PublicState(
        complete, List.of(new CreateChainEvent.ArtifactReady("materialization-result", "result", "result-hash", 8,
            Map.of("chainId", "chain", "outcome", "materialized"))))));

    var recovered = persister.loadDurable("task").orElseThrow();
    assertEquals(TaskState.TASK_STATE_COMPLETED, recovered.task().status().state());
    assertEquals("source", recovered.task().history().getFirst().messageId());
    assertEquals("Keep customerRef unchanged.",
        ((TextPart) recovered.task().history().getFirst().parts().getFirst()).text());
    assertEquals(2, recovered.task().artifacts().size());
    assertEquals(2L, recovered.revision());
    var reread = persister.loadDurable("task").orElseThrow();
    assertEquals(recovered.revision(), reread.revision());
    assertEquals(recovered.task().status().message().messageId(), reread.task().status().message().messageId());
    verify(repository, org.mockito.Mockito.times(1)).transition(anyString(), any());
    verify(facade, never()).start(any());
    verify(facade, never()).continueWithInput(any());
  }

  @Test
  void readRestoresTheExactApprovalWithoutAcceptingIt() {
    persist(snapshot(1, CreateChainExecutionStatus.WORKING, null), List.of(), List.of());
    var approval = new CreateChainPendingAction.Approve("integration-design", "exact-hash", 5,
        "Review the selected endpoint.");
    when(facade.publicState("task")).thenReturn(Optional.of(new CreateChainApplicationFacade.PublicState(
        snapshot(5, CreateChainExecutionStatus.INPUT_REQUIRED, approval), List.of())));

    Task recovered = persister.loadSdkTask("task").orElseThrow();
    assertEquals(TaskState.TASK_STATE_INPUT_REQUIRED, recovered.status().state());
    assertEquals("exact-hash", ((Map<?, ?>) ((org.a2aproject.sdk.spec.DataPart)
        recovered.status().message().parts().get(1)).data()).get("artifactHash"));
    assertEquals(5L, ((Number) recovered.metadata().get("qipRunRevision")).longValue());
    verify(facade, never()).approve(any());
  }

  @Test
  void concurrentProjectionWinsAndAnOldProducerCannotOverwriteIt() {
    persist(snapshot(1, CreateChainExecutionStatus.WORKING, null), List.of(), List.of());
    A2aPersistedTask initial = row.get();
    persist(snapshot(3, CreateChainExecutionStatus.COMPLETED, null), List.of(), List.of());
    A2aPersistedTask winner = row.get();
    row.set(initial);
    when(facade.publicState("task")).thenReturn(Optional.of(new CreateChainApplicationFacade.PublicState(
        snapshot(2, CreateChainExecutionStatus.WORKING, null), List.of())));
    org.mockito.Mockito.doAnswer(invocation -> {
      row.set(winner);
      return new A2aTaskTransitionResult.StaleRevision(winner);
    }).when(repository).transition(anyString(), any());
    var recovered = persister.loadDurable("task").orElseThrow();
    assertEquals(TaskState.TASK_STATE_COMPLETED, recovered.task().status().state());
    assertEquals(winner.revision(), recovered.revision());
    assertThrows(InvalidParamsError.class,
        () -> persist(snapshot(2, CreateChainExecutionStatus.WORKING, null), List.of(), List.of()));
    assertEquals(winner, row.get());
  }

  @Test
  void unrelatedTaskHasNoRuntimeProjection() {
    persist(snapshot(1, CreateChainExecutionStatus.WORKING, null), List.of(), List.of());
    assertEquals(1L, persister.loadDurable("task").orElseThrow().revision());
    verify(repository, never()).transition(anyString(), any());
  }

  @Test
  void previousRunCannotOverwriteItsActiveReplacement() {
    persist(snapshot(10, CreateChainExecutionStatus.WORKING, null), List.of(), List.of());
    var child = new CreateChainExecutionSnapshot("task", "child", CreateChainExecutionStatus.WORKING,
        1L, null, "");
    when(facade.snapshot("task")).thenReturn(Optional.of(child));
    persist(child, List.of(), List.of());
    A2aPersistedTask replacement = row.get();

    assertThrows(InvalidParamsError.class,
        () -> persist(snapshot(11, CreateChainExecutionStatus.COMPLETED, null), List.of(), List.of()));
    assertEquals(replacement, row.get());
  }

  private void persist(CreateChainExecutionSnapshot snapshot, List<CreateChainEvent> events, List<Message> history) {
    persister.persistAndBuildSdkTask("task", "context", CALLER,
        CreateChainA2aStateMapper.project(snapshot, events), history);
  }

  private static CreateChainExecutionSnapshot snapshot(long revision, CreateChainExecutionStatus status,
      CreateChainPendingAction pending) {
    return new CreateChainExecutionSnapshot("task", "run", status, revision, pending, "");
  }

  private static A2aPersistedTask saved(A2aTaskState state, long revision, String json, String history, String artifacts) {
    return new A2aPersistedTask("task", "context", "task", state, revision, "tenant", "subject",
        json, history, artifacts, Instant.EPOCH, Instant.EPOCH, null);
  }
}
