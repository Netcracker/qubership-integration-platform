package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.serverlessworkflow.impl.WorkflowModel;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class ProvidedIdsFlowOrchestratorTest {

  private static final String RUN_ID = "run-1";
  private static final String CONVERSATION_ID = "conversation-1";

  private ProductPipelineRuntime legacy;
  private ProductPipelineRunStore runStore;
  private ProvidedIdsFlow flow;
  private ProvidedIdsFlowTasks tasks;
  private ProvidedIdsFlowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    legacy = mock(ProductPipelineRuntime.class);
    runStore = mock(ProductPipelineRunStore.class);
    flow = mock(ProvidedIdsFlow.class);
    tasks = mock(ProvidedIdsFlowTasks.class);
    Set<String> ownedStages =
        Set.of(
            "ids-entry",
            "requirement-discovery",
            "import-stage",
            "requirement-analysis",
            "design-input",
            "design-planning",
            "design-execution",
            "materialization");
    when(flow.ownsStage(anyString()))
        .thenAnswer(invocation -> ownedStages.contains(invocation.getArgument(0)));
    orchestrator = new ProvidedIdsFlowOrchestrator(legacy, runStore, flow, tasks);
  }

  @Test
  void resumesRunningProvidedRouteThroughFlowAfterRestart() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(document(RunStatus.RUNNING, "design-input")));
    when(legacy.isProvidedDesignRoute(RUN_ID)).thenReturn(true);
    when(legacy.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlow.RunInput input = new ProvidedIdsFlow.RunInput(RUN_ID, "invocation-1");
    when(tasks.begin(RUN_ID)).thenReturn(input);
    when(flow.startInstance(input)).thenReturn(Uni.createFrom().item(mock(WorkflowModel.class)));
    List<PipelineSignal> expected =
        List.of(
            new PipelineSignal.Progress("design-input", "restored"),
            new PipelineSignal.Message("implementation plan ready"));
    when(tasks.finish(input)).thenReturn(new ProvidedIdsFlowTasks.Result(expected, false));

    List<PipelineSignal> actual =
        orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    assertEquals(expected, actual);
    verify(legacy).restoreForExternalWorkflow(command);
    verify(legacy, never()).startOrResume(command);
  }

  @Test
  void resumesTheEntryCrashWindowThroughFlowBeforeTheRouteIsCommitted() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(document(RunStatus.RUNNING, "ids-entry")));
    when(legacy.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlow.RunInput input = new ProvidedIdsFlow.RunInput(RUN_ID, "invocation-1");
    when(tasks.begin(RUN_ID)).thenReturn(input);
    when(flow.startInstance(input)).thenReturn(Uni.createFrom().item(mock(WorkflowModel.class)));
    when(tasks.finish(input))
        .thenReturn(new ProvidedIdsFlowTasks.Result(List.of(), false));

    orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    verify(legacy).restoreForExternalWorkflow(command);
    verify(legacy, never()).startOrResume(command);
    verify(legacy, never()).isProvidedDesignRoute(RUN_ID);
  }

  @Test
  void resumesPostPlanningProvidedRouteThroughFlow() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(document(RunStatus.RUNNING, "design-execution")));
    when(legacy.isProvidedDesignRoute(RUN_ID)).thenReturn(true);
    when(legacy.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlow.RunInput input = new ProvidedIdsFlow.RunInput(RUN_ID, "invocation-1");
    when(tasks.begin(RUN_ID)).thenReturn(input);
    when(flow.startInstance(input)).thenReturn(Uni.createFrom().item(mock(WorkflowModel.class)));
    PipelineSignal progress = new PipelineSignal.Message("Flow execution");
    when(tasks.finish(input))
        .thenReturn(new ProvidedIdsFlowTasks.Result(List.of(progress), false));

    List<PipelineSignal> actual =
        orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    assertEquals(List.of(progress), actual);
    verify(legacy).restoreForExternalWorkflow(command);
    verify(legacy, never()).startOrResume(command);
  }

  @Test
  void keepsDurableImplementationWaitOnTheLegacyResumePath() {
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    when(command.conversationId()).thenReturn(CONVERSATION_ID);
    when(runStore.loadByConversation(CONVERSATION_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_IMPLEMENT, "design-planning")));
    PipelineSignal waiting =
        new PipelineSignal.WaitingForImplement("design-planning", "plan-sha");
    when(legacy.startOrResume(command)).thenReturn(Multi.createFrom().item(waiting));

    List<PipelineSignal> actual =
        orchestrator.startOrResume(command).collect().asList().await().indefinitely();

    assertEquals(List.of(waiting), actual);
    verify(flow, never()).startInstance(any());
  }

  @Test
  void preservesFlowSignalOrderForTheProvidedRoute() {
    AcceptInputCommand command = new AcceptInputCommand(RUN_ID, "provided IDS");
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "ids-entry")));
    when(legacy.recordInput(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlow.RunInput input = new ProvidedIdsFlow.RunInput(RUN_ID, "invocation-1");
    when(tasks.begin(RUN_ID)).thenReturn(input);
    when(flow.startInstance(input)).thenReturn(Uni.createFrom().item(mock(WorkflowModel.class)));
    List<PipelineSignal> expected =
        List.of(
            new PipelineSignal.Progress("ids-entry", "started"),
            new PipelineSignal.Message("IDS accepted"),
            new PipelineSignal.Progress("design-planning", "completed"));
    when(tasks.finish(input)).thenReturn(new ProvidedIdsFlowTasks.Result(expected, false));

    List<PipelineSignal> actual =
        orchestrator.acceptInput(command).collect().asList().await().indefinitely();

    assertEquals(expected, actual);
    verify(legacy, never()).continueRun(RUN_ID);
  }

  @Test
  void continuesPostApprovalInputThroughFlowForTheProvidedRoute() {
    AcceptInputCommand command = new AcceptInputCommand(RUN_ID, "clarification");
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "design-execution")));
    when(legacy.isProvidedDesignRoute(RUN_ID)).thenReturn(true);
    when(legacy.recordInput(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlow.RunInput input = new ProvidedIdsFlow.RunInput(RUN_ID, "invocation-1");
    when(tasks.begin(RUN_ID)).thenReturn(input);
    when(flow.startInstance(input)).thenReturn(Uni.createFrom().item(mock(WorkflowModel.class)));
    PipelineSignal progress = new PipelineSignal.Message("execution resumed");
    when(tasks.finish(input))
        .thenReturn(new ProvidedIdsFlowTasks.Result(List.of(progress), false));

    List<PipelineSignal> actual =
        orchestrator.acceptInput(command).collect().asList().await().indefinitely();

    assertEquals(List.of(progress), actual);
    verify(legacy).recordInput(command);
    verify(legacy, never()).acceptInput(command);
  }

  @Test
  void delegatesTheStandardRouteBackToTheLegacySequence() {
    AcceptInputCommand command = new AcceptInputCommand(RUN_ID, "generate an IDS");
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_INPUT, "ids-entry")));
    when(legacy.recordInput(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlow.RunInput input = new ProvidedIdsFlow.RunInput(RUN_ID, "invocation-1");
    when(tasks.begin(RUN_ID)).thenReturn(input);
    when(flow.startInstance(input)).thenReturn(Uni.createFrom().item(mock(WorkflowModel.class)));
    PipelineSignal entry = new PipelineSignal.Message("route selected");
    PipelineSignal downstream = new PipelineSignal.Message("legacy discovery");
    when(tasks.finish(input))
        .thenReturn(new ProvidedIdsFlowTasks.Result(List.of(entry), true));
    when(legacy.continueRun(RUN_ID)).thenReturn(Multi.createFrom().item(downstream));

    List<PipelineSignal> actual =
        orchestrator.acceptInput(command).collect().asList().await().indefinitely();

    assertEquals(List.of(entry, downstream), actual);
  }

  @Test
  void executesAnApprovedProvidedRouteThroughFlow() {
    ImplementCommand command = new ImplementCommand(RUN_ID, "plan-sha", 3L);
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_IMPLEMENT, "design-planning")));
    when(legacy.isProvidedDesignRoute(RUN_ID)).thenReturn(true);
    when(legacy.recordImplement(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlow.RunInput input = new ProvidedIdsFlow.RunInput(RUN_ID, "invocation-1");
    when(tasks.begin(RUN_ID)).thenReturn(input);
    when(flow.startInstance(input)).thenReturn(Uni.createFrom().item(mock(WorkflowModel.class)));
    PipelineSignal completed = new PipelineSignal.Completed(RunStatus.CHAIN_MATERIALIZED);
    when(tasks.finish(input))
        .thenReturn(new ProvidedIdsFlowTasks.Result(List.of(completed), false));

    List<PipelineSignal> actual =
        orchestrator.implement(command).collect().asList().await().indefinitely();

    assertEquals(List.of(completed), actual);
    verify(legacy).recordImplement(command);
    verify(legacy, never()).implement(command);
  }

  @Test
  void keepsGeneratedRouteImplementationOnLegacy() {
    ImplementCommand command = new ImplementCommand(RUN_ID, "plan-sha", 3L);
    when(runStore.load(RUN_ID))
        .thenReturn(Optional.of(document(RunStatus.WAITING_FOR_IMPLEMENT, "design-planning")));
    when(legacy.isProvidedDesignRoute(RUN_ID)).thenReturn(false);
    PipelineSignal completed = new PipelineSignal.Completed(RunStatus.CHAIN_MATERIALIZED);
    when(legacy.implement(command)).thenReturn(Multi.createFrom().item(completed));

    List<PipelineSignal> actual =
        orchestrator.implement(command).collect().asList().await().indefinitely();

    assertEquals(List.of(completed), actual);
    verify(legacy, never()).recordImplement(command);
    verify(flow, never()).startInstance(any());
  }

  private static ProductPipelineRunDocument document(RunStatus status, String stageId) {
    return new ProductPipelineRunDocument(
        new RunSnapshot(RUN_ID, CONVERSATION_ID, 3L, status, stageId, List.of(), null),
        List.of(),
        List.of(),
        "blob-version");
  }
}
