package org.qubership.integration.platform.ai.a2a.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainA2aArtifactMapper;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainPublicArtifact;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskTransitionResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskUpdate;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;

/**
 * Persists the public A2A Task snapshot before the HTTP response returns.
 *
 * <p>Artifact metadata is stored as opaque JSON keyed by id+revision. Full S3 payloads are never
 * copied into PostgreSQL.
 */
@ApplicationScoped
public class A2aTaskSnapshotPersister {

  private static final TypeReference<List<Map<String, Object>>> ARTIFACT_LIST_TYPE =
      new TypeReference<>() {};

  private final A2aTaskRepository taskRepository;
  private final A2aPersistedSnapshotHook snapshotHook;
  private final ObjectMapper objectMapper;
  private final java.util.concurrent.atomic.AtomicInteger loadDurableCalls =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.concurrent.atomic.AtomicReference<Runnable> beforeLoadDurableHook =
      new java.util.concurrent.atomic.AtomicReference<>();

  @Inject
  public A2aTaskSnapshotPersister(
      A2aTaskRepository taskRepository,
      A2aPersistedSnapshotHook snapshotHook,
      ObjectMapper objectMapper) {
    this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
    this.snapshotHook = Objects.requireNonNull(snapshotHook, "snapshotHook");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /** Test seam: number of coordinated durable reads since the last reset. */
  public int loadDurableCallCountForTest() {
    return loadDurableCalls.get();
  }

  /** Test seam: reset the durable-read counter. */
  public void resetLoadDurableCallCountForTest() {
    loadDurableCalls.set(0);
  }

  /** Test seam: runs once immediately before the next {@link #loadDurable(String)}. */
  void setBeforeLoadDurableHookForTest(Runnable hook) {
    beforeLoadDurableHook.set(hook);
  }

  public PersistResult persistAndBuildSdkTask(
      String taskId,
      String contextId,
      CallerContext caller,
      ProjectedTask projected,
      List<Message> history)
      throws A2AError {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(contextId, "contextId");
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(projected, "projected");
    history = history == null ? List.of() : List.copyOf(history);

    Optional<A2aPersistedTask> existing = taskRepository.findByTaskId(taskId);
    List<CreateChainPublicArtifact> previouslyCommitted =
        existing.map(row -> readArtifacts(row.artifactMetadataJson())).orElse(List.of());
    List<CreateChainPublicArtifact> merged =
        CreateChainA2aArtifactMapper.mergeIdempotent(previouslyCommitted, projected.artifacts());
    List<CreateChainPublicArtifact> newlyCommitted =
        CreateChainA2aArtifactMapper.newlyCommitted(previouslyCommitted, projected.artifacts());

    ProjectedTask durableProjection =
        new ProjectedTask(
            projected.taskId(),
            projected.state(),
            projected.snapshot(),
            projected.pendingAction(),
            projected.pendingActionData(),
            projected.statusText(),
            merged);

    Task sdkTask = toSdkTask(taskId, contextId, durableProjection, history);
    String snapshotJson = toJson(sdkTask);
    String historyJson = toJson(history);
    String artifactJson = toArtifactMetadataJson(merged);
    Instant finalizedAt = durableProjection.terminal() ? Instant.now() : null;

    long durableRevision;
    if (existing.isEmpty()) {
      A2aPersistedTask inserted =
          taskRepository.insert(
              new A2aTaskCreate(
                  taskId,
                  contextId,
                  taskId,
                  durableProjection.state(),
                  caller.tenantId(),
                  caller.subjectId(),
                  snapshotJson,
                  historyJson,
                  artifactJson,
                  finalizedAt));
      durableRevision = inserted.revision();
    } else {
      A2aPersistedTask current = existing.get();
      A2aTaskTransitionResult result =
          taskRepository.transition(
              taskId,
              new A2aTaskUpdate(
                  current.revision(),
                  durableProjection.state(),
                  snapshotJson,
                  historyJson,
                  artifactJson,
                  finalizedAt));
      if (result instanceof A2aTaskTransitionResult.StaleRevision stale) {
        throw A2aProtocolErrorMapper.staleTransition(stale);
      }
      durableRevision = ((A2aTaskTransitionResult.Applied) result).task().revision();
    }

    snapshotHook.onPersisted(
        taskId,
        contextId,
        durableProjection.state(),
        snapshotJson,
        newlyCommitted,
        durableRevision);
    return new PersistResult(sdkTask, newlyCommitted, merged, durableRevision);
  }

  public Optional<Task> loadSdkTask(String taskId) {
    return loadDurable(taskId).map(DurableSnapshot::task);
  }

  /** Loads the durable Task snapshot together with its JDBC revision. */
  public Optional<DurableSnapshot> loadDurable(String taskId) {
    loadDurableCalls.incrementAndGet();
    Runnable hook = beforeLoadDurableHook.getAndSet(null);
    if (hook != null) {
      hook.run();
    }
    return taskRepository
        .findByTaskId(taskId)
        .map(
            row -> {
              try {
                Task task = JsonUtil.fromJson(row.publicSnapshotJson(), Task.class);
                return new DurableSnapshot(task, row.revision());
              } catch (Exception e) {
                throw new IllegalStateException("Unable to deserialize Task snapshot", e);
              }
            });
  }

  private Task toSdkTask(
      String taskId, String contextId, ProjectedTask projected, List<Message> history) {
    Message statusMessage = statusMessage(projected);
    return Task.builder()
        .id(taskId)
        .contextId(contextId)
        .status(new TaskStatus(projected.state().toSdk(), statusMessage, null))
        .artifacts(CreateChainA2aArtifactMapper.toSdkArtifacts(projected.artifacts()))
        .history(history)
        .build();
  }

  private Message statusMessage(ProjectedTask projected) {
    List<org.a2aproject.sdk.spec.Part<?>> parts = new java.util.ArrayList<>();
    if (projected.statusText() != null && !projected.statusText().isBlank()) {
      parts.add(new TextPart(projected.statusText()));
    }
    if (projected.pendingActionData() != null && !projected.pendingActionData().isEmpty()) {
      Map<String, Object> data = new LinkedHashMap<>(projected.pendingActionData());
      parts.add(new DataPart(data));
    }
    if (parts.isEmpty()) {
      parts.add(new TextPart(projected.state().name()));
    }
    return Message.builder()
        .role(Message.Role.ROLE_AGENT)
        .messageId(java.util.UUID.randomUUID().toString())
        .parts(parts)
        .build();
  }

  private List<CreateChainPublicArtifact> readArtifacts(String artifactMetadataJson) {
    if (artifactMetadataJson == null || artifactMetadataJson.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> rows =
          objectMapper.readValue(artifactMetadataJson, ARTIFACT_LIST_TYPE);
      List<CreateChainPublicArtifact> artifacts = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        Object id = row.get("id");
        Object type = row.get("type");
        Object revision = row.get("revision");
        Object hash = row.get("contentHash");
        Object payload = row.get("payload");
        if (!(id instanceof String artifactId)
            || !(type instanceof String artifactType)
            || !(hash instanceof String contentHash)
            || !(revision instanceof Number rev)) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap =
            payload instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        artifacts.add(
            new CreateChainPublicArtifact(
                artifactId, artifactType, rev.longValue(), contentHash, payloadMap));
      }
      return List.copyOf(artifacts);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to deserialize artifact metadata", e);
    }
  }

  private String toArtifactMetadataJson(List<CreateChainPublicArtifact> artifacts) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (CreateChainPublicArtifact artifact : artifacts) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", artifact.id());
      row.put("type", artifact.type());
      row.put("revision", artifact.revision());
      row.put("contentHash", artifact.contentHash());
      row.put("payload", artifact.payload());
      rows.add(row);
    }
    return toJson(rows);
  }

  private String toJson(Object value) {
    try {
      if (value instanceof Task || value instanceof List<?>) {
        try {
          return JsonUtil.toJson(value);
        } catch (Exception ignored) {
          // Fall through to Jackson for mixed maps.
        }
      }
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to serialize A2A snapshot", e);
    }
  }

  /** Result of a durable persist, including which artifacts are new for this revision. */
  public record PersistResult(
      Task task,
      List<CreateChainPublicArtifact> newlyCommittedArtifacts,
      List<CreateChainPublicArtifact> allArtifacts,
      long durableRevision) {

    public PersistResult {
      Objects.requireNonNull(task, "task");
      newlyCommittedArtifacts =
          newlyCommittedArtifacts == null ? List.of() : List.copyOf(newlyCommittedArtifacts);
      allArtifacts = allArtifacts == null ? List.of() : List.copyOf(allArtifacts);
    }

    /** Compatibility constructor used by unit tests that do not assert durable revision. */
    public PersistResult(
        Task task,
        List<CreateChainPublicArtifact> newlyCommittedArtifacts,
        List<CreateChainPublicArtifact> allArtifacts) {
      this(task, newlyCommittedArtifacts, allArtifacts, 1L);
    }
  }

  /** Durable Task snapshot paired with its JDBC revision. */
  public record DurableSnapshot(Task task, long revision) {
    public DurableSnapshot {
      Objects.requireNonNull(task, "task");
    }
  }
}
