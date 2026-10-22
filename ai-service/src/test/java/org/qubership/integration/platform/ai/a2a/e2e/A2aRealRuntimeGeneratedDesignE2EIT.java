package org.qubership.integration.platform.ai.a2a.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Package 05: generated-design path (clarify → approvals → materialization) against a real facade
 * and runtime with deterministic capability adapters.
 */
@QuarkusTest
class A2aRealRuntimeGeneratedDesignE2EIT {

  @Inject A2aTaskRepository taskRepository;

  private A2aRealRuntimeFacadeFactory.Harness harness;

  @BeforeEach
  void installRealFacade() {
    harness = A2aRealRuntimeFacadeFactory.generatedDesignPath();
    QuarkusMock.installMockForType(harness.facade(), CreateChainApplicationFacade.class);
  }

  @Test
  void clarificationThenApprovalsMaterializeOneRun() throws Exception {
    A2aE2eSupport.agentCardOffersCreateChainOverRest();

    String messageId = UUID.randomUUID().toString();
    List<JsonNode> createEvents =
        A2aE2eSupport.streamCreate(
            A2aE2eSupport.textMessageBody(messageId, null, ""), Duration.ofSeconds(60));
    assertFalse(createEvents.isEmpty());
    List<String> createStates = A2aE2eSupport.orderedStates(createEvents);
    assertTrue(createStates.contains("TASK_STATE_WORKING"), createStates.toString());
    assertTrue(createStates.contains("TASK_STATE_INPUT_REQUIRED"), createStates.toString());

    String taskId = A2aE2eSupport.extractTaskId(createEvents.get(0));
    assertTrue(taskRepository.findByTaskId(taskId).isPresent());

    List<JsonNode> clarifyEvents =
        A2aE2eSupport.streamCreate(
            A2aE2eSupport.textMessageBody(
                UUID.randomUUID().toString(), taskId, "create greetings API"),
            Duration.ofSeconds(60));
    assertFalse(clarifyEvents.isEmpty());
    assertTrue(
        A2aE2eSupport.orderedStates(clarifyEvents).contains("TASK_STATE_INPUT_REQUIRED")
            || "TASK_STATE_INPUT_REQUIRED".equals(A2aE2eSupport.getTaskState(taskId))
            || "TASK_STATE_COMPLETED".equals(A2aE2eSupport.getTaskState(taskId)));

    for (int i = 0; i < 8; i++) {
      String state = A2aE2eSupport.getTaskState(taskId);
      if ("TASK_STATE_COMPLETED".equals(state)) {
        break;
      }
      if (!"TASK_STATE_INPUT_REQUIRED".equals(state)) {
        break;
      }
      var pending = A2aE2eSupport.pendingData(taskId);
      if ("approve".equals(String.valueOf(pending.get("action")))) {
        A2aE2eSupport.approvePending(taskId);
        continue;
      }
      if ("clarify".equals(String.valueOf(pending.get("action")))) {
        A2aE2eSupport.streamCreate(
            A2aE2eSupport.textMessageBody(
                UUID.randomUUID().toString(), taskId, "create greetings API"),
            Duration.ofSeconds(60));
        continue;
      }
      break;
    }

    assertEquals("TASK_STATE_COMPLETED", A2aE2eSupport.getTaskState(taskId));
    A2aE2eSupport.assertMaterializationResult(taskId);
    assertEquals(
        RunStatus.CHAIN_MATERIALIZED,
        harness.runStore().loadByConversation(taskId).orElseThrow().run().status());
    assertEquals(1, harness.bindingStore().load(taskId).stream().count());
    assertEquals(1, harness.runStore().loadByConversation(taskId).stream().count());
    A2aE2eSupport.assertNoSensitiveLeak(
        taskRepository.findByTaskId(taskId).orElseThrow().publicSnapshotJson());
  }
}
