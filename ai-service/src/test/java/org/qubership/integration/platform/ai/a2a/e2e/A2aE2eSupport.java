package org.qubership.integration.platform.ai.a2a.e2e;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.qubership.integration.platform.ai.a2a.transport.A2aSseTestSupport;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;

/** Shared HTTP helpers for A2A launch-gate E2E scenarios. */
final class A2aE2eSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private A2aE2eSupport() {}

  static String textMessageBody(String messageId, String taskId, String text) {
    return A2aSseTestSupport.textMessageBody(messageId, taskId, text);
  }

  static String approveBody(String taskId, String type, String hash, long revision) {
    return approveBody(UUID.randomUUID().toString(), taskId, type, hash, revision);
  }

  /** Approve body with a caller-chosen messageId, so a retry can reuse the same receipt key. */
  static String approveBody(
      String messageId, String taskId, String type, String hash, long revision) {
    return """
        {
          "message": {
            "metadata": { "skillId": "create-chain@2" },
            "messageId": "%s",
            "taskId": "%s",
            "role": "ROLE_USER",
            "parts": [ {
              "data": {
                "action": "approve",
                "artifactType": "%s",
                "artifactHash": "%s",
                "revision": %d
              }
            } ]
          }
        }
        """
        .formatted(messageId, taskId, type, hash, revision);
  }

  static String sendMessage(String body) {
    return given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200)
        .extract()
        .path("task.id");
  }

  static Map<?, ?> getTask(String taskId) {
    return given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .when()
        .get(URI.create("/tasks/" + taskId))
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);
  }

  static String getTaskState(String taskId) {
    return given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .when()
        .get(URI.create("/tasks/" + taskId))
        .then()
        .statusCode(200)
        .extract()
        .path("status.state");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> pendingData(String taskId) {
    Object data =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .when()
            .get(URI.create("/tasks/" + taskId))
            .then()
            .statusCode(200)
            .extract()
            .path("status.message.parts.find { it.data != null }.data");
    assertTrue(data instanceof Map, "expected pending data part, got " + data);
    return (Map<String, Object>) data;
  }

  static void approvePending(String taskId) {
    Map<String, Object> pending = pendingData(taskId);
    assertEquals("approve", pending.get("action"), pending.toString());
    String type = String.valueOf(pending.get("artifactType"));
    String hash = String.valueOf(pending.get("artifactHash"));
    long revision = ((Number) pending.get("revision")).longValue();
    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body(approveBody(taskId, type, hash, revision))
        .when()
        .post(URI.create("/message:send"))
        .then()
        .statusCode(200);
  }

  static List<JsonNode> streamCreate(String body, Duration timeout) throws Exception {
    return A2aSseTestSupport.collectSseEvents("POST", "/message:stream", body, timeout);
  }

  static List<JsonNode> subscribe(String taskId, Duration timeout) throws Exception {
    return A2aSseTestSupport.collectSseEvents(
        "POST", "/tasks/" + taskId + ":subscribe", "{}", timeout);
  }

  static String extractTaskId(JsonNode first) {
    if (first.has("id")) {
      return first.get("id").asText();
    }
    if (first.has("task") && first.get("task").has("id")) {
      return first.get("task").get("id").asText();
    }
    throw new AssertionError("Unable to extract task id from " + first);
  }

  static List<String> orderedStates(List<JsonNode> events) {
    List<String> states = new ArrayList<>();
    for (JsonNode event : events) {
      String state = A2aSseTestSupport.eventState(event);
      if (!state.isBlank()) {
        states.add(state);
      }
    }
    return states;
  }

  static void assertNoSensitiveLeak(String payload) {
    assertFalse(payload.contains("s3://"), payload);
    assertFalse(payload.contains("Reference["), payload);
    assertFalse(payload.contains("CompilationArtifacts"), payload);
    assertFalse(payload.contains("password"), payload);
    assertFalse(payload.contains("apiKey"), payload);
    assertFalse(payload.contains("prompt:"), payload);
  }

  static void assertMaterializationResult(String taskId) {
    assertEquals("TASK_STATE_COMPLETED", getTaskState(taskId));
    String body =
        given()
            .urlEncodingEnabled(false)
            .header("A2A-Version", "1.0")
            .when()
            .get(URI.create("/tasks/" + taskId))
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertNoSensitiveLeak(body);
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (Exception e) {
      throw new AssertionError("Get Task body is not JSON: " + body, e);
    }
    JsonNode artifacts = root.path("artifacts");
    assertTrue(artifacts.isArray(), "expected artifacts array: " + artifacts);
    JsonNode materialization = null;
    for (JsonNode artifact : artifacts) {
      String name = artifact.path("name").asText("");
      String metaType = artifact.path("metadata").path("type").asText("");
      if (CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT.equals(name)
          || CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT.equals(metaType)) {
        materialization = artifact;
        break;
      }
    }
    assertNotNull(
        materialization, "missing materialization-result artifact object in " + artifacts);

    String artifactId = materialization.path("artifactId").asText(null);
    assertNotNull(artifactId, "materialization artifactId missing: " + materialization);
    assertFalse(artifactId.isBlank(), "materialization artifactId blank: " + materialization);
    assertEquals(
        CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
        materialization.path("name").asText(),
        materialization.toString());

    JsonNode metadata = materialization.path("metadata");
    assertTrue(metadata.isObject(), "materialization metadata missing: " + materialization);
    assertEquals(
        CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
        metadata.path("type").asText(),
        metadata.toString());
    assertTrue(metadata.path("revision").isNumber(), "revision must be numeric: " + metadata);
    long revision = metadata.path("revision").asLong();
    assertTrue(revision > 0, "revision must be positive: " + metadata);
    String contentHash = metadata.path("contentHash").asText(null);
    assertNotNull(contentHash, "contentHash missing: " + metadata);
    assertFalse(contentHash.isBlank(), "contentHash blank: " + metadata);

    JsonNode dataPayload = null;
    for (JsonNode part : materialization.path("parts")) {
      if (part.has("data") && part.get("data").isObject()) {
        dataPayload = part.get("data");
        break;
      }
    }
    assertNotNull(dataPayload, "materialization data part missing: " + materialization);
    assertEquals(artifactId, dataPayload.path("id").asText(), dataPayload.toString());
    assertEquals(
        CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
        dataPayload.path("type").asText(),
        dataPayload.toString());
    assertEquals(revision, dataPayload.path("revision").asLong(), dataPayload.toString());
    assertEquals(contentHash, dataPayload.path("contentHash").asText(), dataPayload.toString());
    assertFalse(
        dataPayload.has("contentRef") && dataPayload.path("contentRef").asText().contains("app://"),
        dataPayload.toString());
  }

  static void cancelRejected(String taskId) {
    given()
        .urlEncodingEnabled(false)
        .header("A2A-Version", "1.0")
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(URI.create("/tasks/" + taskId + ":cancel"))
        .then()
        .statusCode(409);
  }

  /** The card these runs drive: create-chain reachable, REST endpoint at the published base. */
  static void agentCardOffersCreateChainOverRest() {
    given()
        .when()
        .get("/.well-known/agent-card.json")
        .then()
        .statusCode(200)
        .body("skills.id", org.hamcrest.Matchers.hasItem("create-chain@2"))
        .body(
            "supportedInterfaces.find { it.protocolBinding == 'HTTP+JSON' }.url",
            equalTo("http://a2a.test.local"));
  }
}
