package org.qubership.integration.platform.ai.a2a;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/**
 * When A2A is disabled, discovery and invocation are unavailable while browser chat and readiness
 * stay up. Persisted Task data is not deleted by the disable path.
 */
@QuarkusTest
@TestProfile(A2aSdkBootDisabledProfile.class)
class A2aFeatureFlagDisabledIT {

  @Inject A2aFeatureGate featureGate;

  @Inject A2aTaskRepository taskRepository;

  @InjectMock ScenarioRouter router;

  @InjectMock ProductPipelineRunStore runStore;

  @BeforeEach
  void stubBrowserDependencies() {
    when(runStore.loadByConversation(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void disabledBlocksA2aButKeepsBrowserReady() {
    org.junit.jupiter.api.Assertions.assertFalse(featureGate.enabled());

    given()
        .when()
        .get("/.well-known/agent-card.json")
        .then()
        .statusCode(equalTo(503))
        .body(containsString(A2aFeatureGate.DISABLED_MESSAGE));

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/message:send")
        .then()
        .statusCode(equalTo(503))
        .body(containsString(A2aFeatureGate.DISABLED_MESSAGE));

    when(router.route(any(ChatRequest.class), anyString()))
        .thenReturn(Multi.createFrom().item(ChatEvent.token("browser still works")));

    given()
        .contentType(ContentType.JSON)
        .body("{\"message\":\"hello from browser\"}")
        .when()
        .post("/api/v1/chat")
        .then()
        .statusCode(200)
        .body(containsString("event: meta"))
        .body(containsString("browser still works"))
        .body(containsString("event: done"));

    given().when().get("/q/health").then().statusCode(not(equalTo(404)));
  }

  /**
   * Rollback keeps Task data intact. {@code A2aFeatureFlagIT} proves a persisted non-terminal Task
   * is readable while the feature is on; this proves the disable path neither deletes nor rewrites
   * that row, so re-enabling finds it unchanged.
   */
  @Test
  void disableLeavesPersistedTaskDataIntact() {
    String taskId = "rollback-" + UUID.randomUUID();
    taskRepository.insert(
        new A2aTaskCreate(
            taskId,
            taskId,
            taskId,
            A2aTaskState.INPUT_REQUIRED,
            "local",
            "local-user",
            "{\"id\":\"" + taskId + "\"}",
            "[]",
            "[]",
            null));

    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(A2aTaskState.INPUT_REQUIRED, persisted.state());

    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .when()
        .get("/tasks/" + taskId)
        .then()
        .statusCode(equalTo(503))
        .body(containsString(A2aFeatureGate.DISABLED_MESSAGE));

    A2aPersistedTask afterDisabledRead = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(persisted.publicSnapshotJson(), afterDisabledRead.publicSnapshotJson());
    assertEquals(persisted.revision(), afterDisabledRead.revision());
    assertEquals(A2aTaskState.INPUT_REQUIRED, afterDisabledRead.state());
  }
}
