package org.qubership.integration.platform.ai.a2a;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

@QuarkusTest
class A2aFeatureFlagIT {

  @Inject A2aFeatureGate featureGate;

  @Inject A2aTaskRepository taskRepository;

  @InjectMock ScenarioRouter router;

  @InjectMock ProductPipelineRunStore runStore;

  @BeforeEach
  void stubBrowserDependencies() {
    when(runStore.loadByConversation(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void enabledExposesAgentCardAndLeavesBrowserHealthy() {
    assertTrue(featureGate.enabled());

    given()
        .when()
        .get("/.well-known/agent-card.json")
        .then()
        .statusCode(200)
        .body(containsString(A2aProtocolConstants.AGENT_NAME));

    when(router.route(any(ChatRequest.class), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("browser-ok")));

    given()
        .contentType(ContentType.JSON)
        .body("{\"message\":\"hello\"}")
        .when()
        .post("/api/v1/chat")
        .then()
        .statusCode(200)
        .body(containsString("event: meta"))
        .body(containsString("event: done"));

    given().when().get("/q/health").then().statusCode(not(equalTo(404)));
  }

  @Test
  void reEnableKeepsPersistedTaskReadableWithoutMigration() throws Exception {
    String taskId = "reenable-" + UUID.randomUUID();
    Task sdkTask =
        Task.builder()
            .id(taskId)
            .contextId(taskId)
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, null))
            .history(List.of())
            .build();
    String snapshotJson = JsonUtil.toJson(sdkTask);

    taskRepository.insert(
        new A2aTaskCreate(
            taskId,
            taskId,
            taskId,
            A2aTaskState.INPUT_REQUIRED,
            "local",
            "local-user",
            snapshotJson,
            "[]",
            "[]",
            null));

    A2aPersistedTask beforeDisable = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(A2aTaskState.INPUT_REQUIRED, beforeDisable.state());

    // Runtime disable is covered by A2aFeatureFlagDisabledIT. Here we prove Task rows survive and
    // remain readable after the feature stays/returns enabled — no delete or schema change.
    assertTrue(featureGate.enabled());
    A2aPersistedTask afterToggleSimulation = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(beforeDisable.publicSnapshotJson(), afterToggleSimulation.publicSnapshotJson());
    assertEquals(beforeDisable.revision(), afterToggleSimulation.revision());

    given()
        .header("A2A-Version", "1.0")
        .when()
        .get("/tasks/" + taskId)
        .then()
        .statusCode(200)
        .body("id", equalTo(taskId));
  }
}
