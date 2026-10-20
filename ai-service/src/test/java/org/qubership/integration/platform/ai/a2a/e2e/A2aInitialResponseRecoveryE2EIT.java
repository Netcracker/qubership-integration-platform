package org.qubership.integration.platform.ai.a2a.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * Mandatory launch scenario: lost initial response recovery by caller-scoped messageId without
 * creating a second pipeline run.
 */
@QuarkusTest
class A2aInitialResponseRecoveryE2EIT {

  @InjectMock CreateChainApplicationFacade facade;

  @Inject A2aTaskRepository taskRepository;

  @Inject A2aMessageReceiptRepository receiptRepository;

  private final AtomicInteger startCalls = new AtomicInteger();

  @BeforeEach
  void stubFacade() {
    startCalls.set(0);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              StartCreateChainCommand command = invocation.getArgument(0);
              return Multi.createFrom()
                  .item(
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())));
            });
    when(facade.snapshot(any()))
        .thenAnswer(
            invocation -> {
              String taskId = invocation.getArgument(0);
              return Optional.of(
                  new CreateChainExecutionSnapshot(
                      taskId,
                      "run-" + taskId,
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L,
                      new CreateChainPendingAction.Clarify(
                          "Additional input is required.", List.of()),
                      ""));
            });
  }

  @Test
  void retryInitialMessageWithoutTaskIdReturnsOriginalTask() {
    String messageId = UUID.randomUUID().toString();
    String initialBody =
        A2aE2eSupport.textMessageBody(messageId, null, "Create chain after lost response");

    String originalTaskId = A2aE2eSupport.sendMessage(initialBody);
    assertEquals(1, startCalls.get());
    assertTrue(
        receiptRepository
            .findTaskIdForCallerMessage("local", "local-user", messageId)
            .filter(originalTaskId::equals)
            .isPresent());

    // Client never received the response: retry the same Message without taskId.
    String recoveredTaskId = A2aE2eSupport.sendMessage(initialBody);

    assertEquals(originalTaskId, recoveredTaskId);
    assertEquals(1, startCalls.get(), "facade.start must run once");
    assertEquals(1, taskRepository.findByTaskId(originalTaskId).stream().count());
    assertEquals(
        originalTaskId,
        receiptRepository
            .findTaskIdForCallerMessage("local", "local-user", messageId)
            .orElseThrow());
  }
}
