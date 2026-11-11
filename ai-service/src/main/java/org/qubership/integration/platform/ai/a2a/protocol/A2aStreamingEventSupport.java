package org.qubership.integration.platform.ai.a2a.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.a2aproject.sdk.grpc.utils.ProtoJsonUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.util.sse.SseFormatter;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

/**
 * Builds and SSE-formats the A2A streaming event types required by later prompts.
 */
public final class A2aStreamingEventSupport {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private A2aStreamingEventSupport() {}

  public static Task initialTask(
      String taskId, String contextId, A2aTaskState state, String statusText) {
    return Task.builder()
        .id(taskId)
        .contextId(contextId)
        .status(new TaskStatus(state.toSdk(), statusMessage(statusText), null))
        .build();
  }

  public static TaskStatusUpdateEvent statusUpdate(
      String taskId, String contextId, A2aTaskState state, String statusText) {
    return new TaskStatusUpdateEvent(
        taskId, new TaskStatus(state.toSdk(), statusMessage(statusText), null), contextId, null);
  }

  public static TaskArtifactUpdateEvent artifactUpdate(
      String taskId,
      String contextId,
      String artifactId,
      String artifactName,
      JsonNode structuredPayload) {
    Map<String, Object> data = new ObjectMapper().convertValue(structuredPayload, MAP_TYPE);
    Artifact artifact =
        Artifact.builder()
            .artifactId(artifactId)
            .name(artifactName)
            .parts(List.of(new DataPart(data)))
            .build();
    return new TaskArtifactUpdateEvent(taskId, artifact, contextId, false, true, null);
  }

  public static String toSse(Object event, ObjectMapper objectMapper) {
    if (objectMapper == null) {
      throw new IllegalArgumentException("objectMapper is required");
    }
    if (!(event instanceof StreamingEventKind streamingEvent)) {
      throw new IllegalArgumentException("Event must implement StreamingEventKind");
    }
    try {
      String json =
          ProtoJsonUtils.toJson(
              JsonFormat.printer().omittingInsignificantWhitespace(),
              ProtoUtils.ToProto.taskOrMessageStream(streamingEvent));
      return SseFormatter.formatJsonAsSSE(json, 0L);
    } catch (InvalidProtocolBufferException | RuntimeException protoFailure) {
      try {
        return SseFormatter.formatJsonAsSSE(JsonUtil.toJson(streamingEvent), 0L);
      } catch (JsonProcessingException jsonFailure) {
        jsonFailure.addSuppressed(protoFailure);
        throw new IllegalStateException("Unable to serialize streaming event for SSE", jsonFailure);
      }
    }
  }

  private static Message statusMessage(String text) {
    return Message.builder()
        .role(Message.Role.ROLE_AGENT)
        .messageId(UUID.randomUUID().toString())
        .parts(List.of(new TextPart(text)))
        .build();
  }
}
