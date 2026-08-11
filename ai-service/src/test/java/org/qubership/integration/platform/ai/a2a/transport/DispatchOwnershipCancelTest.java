package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.CallerContextProvider;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aAgentExecutor.DispatchOwnershipLostException;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

class DispatchOwnershipCancelTest {

  private DispatchLeaseHeartbeat heartbeat;

  @AfterEach
  void tearDown() {
    if (heartbeat != null) {
      heartbeat.shutdown();
    }
  }

  @Test
  void ownershipLossCancelsSilentPublisherBeforePersist() throws Exception {
    heartbeat =
        new DispatchLeaseHeartbeat(
            Duration.ofMillis(50),
            Duration.ofSeconds(1),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());

    CountDownLatch subscribed = new CountDownLatch(1);
    CountDownLatch releaseLate = new CountDownLatch(1);
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicInteger persistCalls = new AtomicInteger();

    CreateChainApplicationFacade facade = mock(CreateChainApplicationFacade.class);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenReturn(
            Multi.createFrom()
                .emitter(
                    emitter -> {
                      subscribed.countDown();
                      try {
                        assertTrue(releaseLate.await(5, TimeUnit.SECONDS));
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        emitter.fail(e);
                        return;
                      }
                      if (cancelled.get()) {
                        emitter.complete();
                        return;
                      }
                      emitter.emit(new CreateChainEvent.Progress("late"));
                      emitter.complete();
                    }));
    when(facade.snapshot(any()))
        .thenReturn(
            Optional.of(
                new CreateChainExecutionSnapshot(
                    "task-1",
                    "run-1",
                    CreateChainExecutionStatus.WORKING,
                    1L,
                    null,
                    "")));

    A2aMessageReceiptRepository receipts = mock(A2aMessageReceiptRepository.class);
    UUID owner = UUID.randomUUID();
    when(receipts.claimInitialWithWorkingTask(any(), any(), any(), any(), any(), any()))
        .thenReturn(new A2aCallerMessageClaimResult.Claimed("task-1"));
    when(receipts.acquireDispatch(any(), any(), any()))
        .thenReturn(A2aDispatchAcquisition.acquired(owner));
    when(receipts.renewDispatch(any(), any(), any(), any())).thenReturn(true);

    A2aTaskSnapshotPersister persister = mock(A2aTaskSnapshotPersister.class);
    when(persister.loadSdkTask(any())).thenReturn(Optional.empty());
    when(persister.persistAndBuildSdkTask(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              persistCalls.incrementAndGet();
              throw new AssertionError("persist must not run after ownership loss");
            });

    CallerContextProvider callers = () -> new CallerContext("local", "local-user");
    TaskAccessPolicy access = mock(TaskAccessPolicy.class);
    CreateChainA2aAgentExecutor executor =
        new CreateChainA2aAgentExecutor(
            facade, persister, receipts, callers, access, null, null, heartbeat);

    RequestContext context = mock(RequestContext.class);
    when(context.getTaskId()).thenReturn("task-1");
    when(context.getContextId()).thenReturn("task-1");
    when(context.getTask()).thenReturn(null);
    when(context.getMessage())
        .thenReturn(
            Message.builder()
                .messageId("msg-1")
                .role(Message.Role.ROLE_USER)
                .parts(List.of(new TextPart("hello")))
                .build());
    when(context.getMetadata()).thenReturn(java.util.Map.of());

    AgentEmitter emitter = mock(AgentEmitter.class);
    Thread worker =
        new Thread(
            () -> {
              try {
                executor.execute(context, emitter);
              } catch (DispatchOwnershipLostException expected) {
                cancelled.set(true);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    worker.start();
    assertTrue(subscribed.await(5, TimeUnit.SECONDS));
    when(receipts.renewDispatch(any(), any(), any(), any())).thenReturn(false);
    heartbeat.tickAllSynchronouslyForTest();
    releaseLate.countDown();
    worker.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(worker.isAlive());
    assertEquals(0, persistCalls.get());
    assertEquals(0, executor.activeExecutionCountForTest());
    assertEquals(0, heartbeat.activeCountForTest());
    verify(receipts, never())
        .completeDispatch(
            any(),
            any(),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void blockedRenewalDoesNotStarveSiblingDispatch() throws Exception {
    CountDownLatch aStarted = new CountDownLatch(1);
    CountDownLatch aRelease = new CountDownLatch(1);
    CountDownLatch bRenewed = new CountDownLatch(2);
    heartbeat =
        new DispatchLeaseHeartbeat(
            Duration.ofMillis(20),
            Duration.ofSeconds(1),
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());

    AutoCloseable a =
        heartbeat.start(
            UUID.randomUUID(),
            () -> {
              aStarted.countDown();
              try {
                assertTrue(aRelease.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return true;
            },
            () -> {});
    AutoCloseable b =
        heartbeat.start(
            UUID.randomUUID(),
            () -> {
              bRenewed.countDown();
              return true;
            },
            () -> {});

    heartbeat.tickAllForTest();
    assertTrue(aStarted.await(5, TimeUnit.SECONDS));
    heartbeat.tickAllForTest();
    heartbeat.tickAllForTest();
    assertTrue(bRenewed.await(5, TimeUnit.SECONDS), "B must renew while A is blocked");
    aRelease.countDown();
    a.close();
    b.close();
  }
}
