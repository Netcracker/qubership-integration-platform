package org.qubership.integration.platform.ai.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aStreamingEventSupport;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Slice 3: selected path can represent Task, status update, artifact update, and SSE frames.
 */
class A2aStreamingTypesCompatibilityTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void representsInitialTaskStatusArtifactAndSseFrames() throws Exception {
    Task initialTask =
        A2aStreamingEventSupport.initialTask(
            "task-1", "ctx-1", A2aTaskState.WORKING, "Starting create-chain@2");
    TaskStatusUpdateEvent statusUpdate =
        A2aStreamingEventSupport.statusUpdate(
            "task-1", "ctx-1", A2aTaskState.WORKING, "Gathering requirements");
    TaskArtifactUpdateEvent artifactUpdate =
        A2aStreamingEventSupport.artifactUpdate(
            "task-1",
            "ctx-1",
            "artifact-1",
            "requirement-draft",
            objectMapper.createObjectNode().put("summary", "Need CRM sync"));

    assertEquals("task-1", initialTask.id());
    assertEquals("ctx-1", initialTask.contextId());
    assertEquals(A2aTaskState.WORKING.toSdk(), initialTask.status().state());
    assertEquals("task", initialTask.kind());

    assertEquals("task-1", statusUpdate.taskId());
    assertEquals("statusUpdate", statusUpdate.kind());
    assertEquals(A2aTaskState.WORKING.toSdk(), statusUpdate.status().state());

    assertEquals("task-1", artifactUpdate.taskId());
    assertEquals("artifactUpdate", artifactUpdate.kind());
    assertEquals("requirement-draft", artifactUpdate.artifact().name());

    String taskSse = A2aStreamingEventSupport.toSse(initialTask, objectMapper);
    String statusSse = A2aStreamingEventSupport.toSse(statusUpdate, objectMapper);
    String artifactSse = A2aStreamingEventSupport.toSse(artifactUpdate, objectMapper);

    assertTrue(taskSse.startsWith("data: "));
    assertTrue(taskSse.contains("\"task-1\"") || taskSse.contains("task-1"));
    assertTrue(taskSse.endsWith("\n\n"));
    assertTrue(statusSse.startsWith("data: "));
    assertTrue(statusSse.contains("task-1"));
    assertTrue(artifactSse.startsWith("data: "));
    assertTrue(artifactSse.contains("requirement-draft"));

    JsonNode artifactJson = objectMapper.readTree(artifactSse.substring("data: ".length(), artifactSse.indexOf('\n')));
    assertTrue(
        artifactJson.toString().contains("requirement-draft")
            || artifactJson.toString().contains("artifact-1"));
  }
}
