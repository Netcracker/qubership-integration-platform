package org.qubership.integration.platform.ai.a2a.artifacts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aStreamingEventSupport;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.A2aProtocolErrorMapper;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;

class A2aLeakTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<String> FORBIDDEN =
      List.of(
          "s3://",
          "product-pipeline-",
          "compiler-artifacts/",
          "bucketName",
          "objectKey",
          "modelTrace",
          "credentials",
          "Reference[",
          "WAITING_FOR_APPROVAL",
          "pipelineSnapshot");

  @Test
  void projectedTaskAndArtifactsOmitForbiddenInternalFields() throws Exception {
    Map<String, Object> dirty = new LinkedHashMap<>();
    dirty.put("summary", "Public summary");
    dirty.put("bucket", "secret-bucket");
    dirty.put("objectKey", "compiler-artifacts/run/x");
    dirty.put("prompt", "system prompt leak");
    dirty.put("modelTrace", Map.of("tokens", 9));
    dirty.put("credentials", "token-value");
    dirty.put("pipelineSnapshot", Map.of("status", "WAITING_FOR_APPROVAL"));

    CreateChainPublicArtifact artifact =
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "leak-1",
                    CreateChainPublicArtifactTypes.VALIDATION_REPORT,
                    4L,
                    "1".repeat(64),
                    dirty))
            .orElseThrow();

    ProjectedTask projected =
        CreateChainA2aStateMapper.project(
            new CreateChainExecutionSnapshot(
                "task-leak",
                "run-leak",
                CreateChainExecutionStatus.INPUT_REQUIRED,
                4L,
                new CreateChainPendingAction.Approve(
                    CreateChainPublicArtifactTypes.VALIDATION_REPORT,
                    "1".repeat(64),
                    4L,
                    "Review validation"),
                ""),
            List.of(
                new CreateChainEvent.ArtifactReady(
                    CreateChainPublicArtifactTypes.VALIDATION_REPORT,
                    "leak-1",
                    "1".repeat(64),
                    4L),
                new CreateChainEvent.Waiting(
                    new CreateChainPendingAction.Approve(
                        CreateChainPublicArtifactTypes.VALIDATION_REPORT,
                        "1".repeat(64),
                        4L,
                        "Review validation"))));

    String artifactJson = MAPPER.writeValueAsString(artifact.payload());
    String pendingJson = MAPPER.writeValueAsString(projected.pendingActionData());
    assertNoLeaks(artifactJson);
    assertNoLeaks(pendingJson);
    assertFalse(artifactJson.contains("secret-bucket"));
    assertTrue(artifactJson.contains("Public summary"));
  }

  @Test
  void sseArtifactFramesOmitForbiddenFields() throws Exception {
    CreateChainPublicArtifact artifact =
        CreateChainPublicArtifactProjector.project(
                new CreateChainArtifactEvidence(
                    "sse-1",
                    CreateChainPublicArtifactTypes.FAILURE_REPORT,
                    1L,
                    "2".repeat(64),
                    Map.of(
                        "summary",
                        "Failed safely",
                        "bucket",
                        "pipelines",
                        "rawLog",
                        "stacktrace")))
            .orElseThrow();
    TaskArtifactUpdateEvent event =
        A2aStreamingEventSupport.artifactUpdate(
            "task-1",
            "ctx-1",
            artifact.id(),
            artifact.type(),
            MAPPER.valueToTree(artifact.payload()));
    String sse = A2aStreamingEventSupport.toSse(event, MAPPER);
    assertNoLeaks(sse);
    assertTrue(sse.contains("failure-report") || sse.contains("sse-1"));
  }

  @Test
  void protocolErrorsOmitStorageCoordinatesAndRawLogs() {
    InvalidParamsError wrongHash =
        (InvalidParamsError)
            A2aProtocolErrorMapper.fromApproveOutcome(
                new org.qubership.integration.platform.ai.productpipeline.create.facade
                    .ApproveCreateChainOutcome.WrongArtifactHash("expected", "provided"));
    UnsupportedOperationError implement =
        (UnsupportedOperationError) A2aProtocolErrorMapper.unsupportedImplementAction();
    String combined = wrongHash.getMessage() + " " + implement.getMessage();
    assertNoLeaks(combined);
    assertFalse(combined.toLowerCase().contains("s3"));
  }

  @Test
  void initialTaskSnapshotHasNoForbiddenMarkers() throws Exception {
    Task task =
        A2aStreamingEventSupport.initialTask(
            "task-1", "ctx-1", A2aTaskState.WORKING, "Working");
    String json = MAPPER.writeValueAsString(Map.of("id", task.id(), "state", task.status().state()));
    assertNoLeaks(json);
  }

  private static void assertNoLeaks(String text) {
    for (String forbidden : FORBIDDEN) {
      assertFalse(text.contains(forbidden), () -> "leaked '" + forbidden + "' in: " + text);
    }
  }
}
