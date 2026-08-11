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
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRuntime;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Package 05: provided-IDS-style happy path against a real {@link CreateChainApplicationFacade} and
 * {@link ProductPipelineRuntime} (deterministic capability adapters; no Mockito facade mock).
 */
@QuarkusTest
class A2aRealRuntimeProvidedIdsE2EIT {

  @Inject A2aTaskRepository taskRepository;

  private A2aRealRuntimeFacadeFactory.Harness harness;

  @BeforeEach
  void installRealFacade() {
    harness = A2aRealRuntimeFacadeFactory.providedIdsPath();
    QuarkusMock.installMockForType(harness.facade(), CreateChainApplicationFacade.class);
  }

  @Test
  void providedRequirementsReachMaterializationThroughRealFacade() throws Exception {
    A2aE2eSupport.agentCardOffersCreateChainOverRest();

    String messageId = UUID.randomUUID().toString();
    String idsLikeBody =
        A2aE2eSupport.textMessageBody(
            messageId,
            null,
            "# Integration Design Specification\\nIntegration flow for CIP Chain - Pets GET /pets");

    List<JsonNode> createEvents =
        A2aE2eSupport.streamCreate(idsLikeBody, Duration.ofSeconds(60));
    assertFalse(createEvents.isEmpty());
    List<String> createStates = A2aE2eSupport.orderedStates(createEvents);
    assertTrue(createStates.contains("TASK_STATE_WORKING"), createStates.toString());
    assertTrue(
        createStates.contains("TASK_STATE_INPUT_REQUIRED"),
        "expected live progress then approval wait: " + createStates);

    String taskId = A2aE2eSupport.extractTaskId(createEvents.get(0));
    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(taskId, persisted.conversationId());
    assertEquals(A2aTaskState.INPUT_REQUIRED, persisted.state());
    A2aE2eSupport.assertNoSensitiveLeak(persisted.publicSnapshotJson());
    assertFalse(persisted.publicSnapshotJson().contains("app://"));

    // Approve every pending artifact until COMPLETED (requirement draft → plan → auto-implement).
    for (int i = 0; i < 6; i++) {
      String state = A2aE2eSupport.getTaskState(taskId);
      if ("TASK_STATE_COMPLETED".equals(state)) {
        break;
      }
      if ("TASK_STATE_INPUT_REQUIRED".equals(state)) {
        var pending = A2aE2eSupport.pendingData(taskId);
        if ("approve".equals(String.valueOf(pending.get("action")))) {
          A2aE2eSupport.approvePending(taskId);
          continue;
        }
        if ("clarify".equals(String.valueOf(pending.get("action")))) {
          String clarifyBody =
              A2aE2eSupport.textMessageBody(
                  UUID.randomUUID().toString(), taskId, "create greetings API");
          A2aE2eSupport.streamCreate(clarifyBody, Duration.ofSeconds(60));
          continue;
        }
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

    String getBody =
        io.restassured.RestAssured.given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .when()
            .get(java.net.URI.create("/tasks/" + taskId))
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertTrue(getBody.contains(CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT), getBody);
    A2aE2eSupport.assertNoSensitiveLeak(getBody);
  }
}
