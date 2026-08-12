package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.LocalPermitAllTaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.A2aTaskSnapshotPersister;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aAgentExecutor;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * Proves browser and A2A adapters call the same {@link CreateChainApplicationFacade} while
 * serializing different transport events (ChatEvent vs A2A Task frames).
 */
class SharedApplicationFacadeAdaptersTest {

  @Test
  void browserAndA2aAdaptersInvokeSameFacadeWithDifferentSerialization() throws Exception {
    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenReturn(
            Multi.createFrom()
                .items(
                    new CreateChainEvent.Progress("Working"),
                    new CreateChainEvent.Waiting(
                        new CreateChainPendingAction.Clarify("Need detail", List.of()))));
    when(facade.snapshot("shared-1"))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "shared-1",
                    "run-1",
                    CreateChainExecutionStatus.INPUT_REQUIRED,
                    1L,
                    new CreateChainPendingAction.Clarify("Need detail", List.of()),
                    "")));

    List<ChatEvent> browserEvents = collect(browserStart(facade, "shared-1", "Build a chain"));
    assertTrue(browserEvents.stream().anyMatch(e -> e instanceof ChatEvent.Token));
    assertTrue(browserEvents.stream().anyMatch(e -> e instanceof ChatEvent.Decision));
    assertTrue(browserEvents.stream().noneMatch(e -> e instanceof ChatEvent.Meta));

    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    when(receipts.claimInitialWithWorkingTask(
            eq("local"), eq("local-user"), eq("msg-1"), any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult
                .Claimed("shared-1"));
    when(receipts.acquireDispatch(eq("local"), eq("local-user"), eq("msg-1")))
        .thenReturn(
            org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition.acquired(
                java.util.UUID.randomUUID()));
    when(receipts.renewDispatch(any(), any(), any(), any())).thenReturn(true);
    when(persister.loadSdkTask("shared-1")).thenReturn(Optional.empty());
    when(persister.persistAndBuildSdkTask(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ProjectedTask projected = invocation.getArgument(3);
              org.a2aproject.sdk.spec.Task task =
                  org.a2aproject.sdk.spec.Task.builder()
                      .id("shared-1")
                      .contextId("ctx-1")
                      .status(
                          new org.a2aproject.sdk.spec.TaskStatus(
                              projected.state().toSdk(), null, null))
                      .build();
              return new A2aTaskSnapshotPersister.PersistResult(task, List.of(), List.of());
            });

    CreateChainA2aAgentExecutor a2a =
        new CreateChainA2aAgentExecutor(
            facade,
            persister,
            receipts,
            () -> new CallerContext("local", "local-user"),
            new LocalPermitAllTaskAccessPolicy());
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .messageId("msg-1")
            .parts(List.of(new TextPart("Build a chain")))
            .build();
    RequestContext context =
        new RequestContext.Builder()
            .setTaskId("shared-1")
            .setContextId("ctx-1")
            .setParams(org.a2aproject.sdk.spec.MessageSendParams.builder().message(message).build())
            .build();
    AgentEmitter emitter = mock(AgentEmitter.class);
    a2a.execute(context, emitter);

    verify(facade, times(2)).start(any(StartCreateChainCommand.class));
    verify(emitter).requiresInput(any(), org.mockito.ArgumentMatchers.eq(true));
    assertEquals(A2aTaskState.INPUT_REQUIRED, projectedStateFromLastPersist(persister));
    assertInstanceOf(ChatEvent.Token.class, browserEvents.get(0));
  }

  /**
   * Browser-side adapter double: maps facade events to ChatEvent frames without A2A Task DTOs.
   */
  static Multi<ChatEvent> browserStart(
      CreateChainApplicationFacade facade, String conversationId, String text) {
    return facade
        .start(new StartCreateChainCommand(conversationId, text))
        .map(
            event ->
                switch (event) {
                  case CreateChainEvent.Progress progress -> ChatEvent.token(progress.label());
                  case CreateChainEvent.Message message -> ChatEvent.token(message.text());
                  case CreateChainEvent.Waiting waiting ->
                      ChatEvent.decision(waiting.pendingAction(), 0L, "");
                  case CreateChainEvent.ArtifactReady artifact ->
                      ChatEvent.token(artifact.artifactType());
                  case CreateChainEvent.Completed completed ->
                      ChatEvent.token("completed:" + completed.snapshot().taskId());
                  case CreateChainEvent.Failed failed -> ChatEvent.error(failed.message());
                });
  }

  private static List<ChatEvent> collect(Multi<ChatEvent> events) {
    return new ArrayList<>(events.collect().asList().await().indefinitely());
  }

  private static A2aTaskState projectedStateFromLastPersist(A2aTaskSnapshotPersister persister)
      throws Exception {
    var interaction =
        org.mockito.Mockito.mockingDetails(persister).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("persistAndBuildSdkTask"))
            .reduce((a, b) -> b)
            .orElseThrow();
    ProjectedTask projected = (ProjectedTask) interaction.getArgument(3);
    return projected.state();
  }
}
