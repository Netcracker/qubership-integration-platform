package org.qubership.integration.platform.ai.a2a.transport;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainA2aArtifactMapper;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainPublicArtifact;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Publishes persisted Task status and artifact updates to the in-memory {@link TaskEventHub}.
 *
 * <p>Runs after JDBC persistence so subscribers cannot observe a revision before it is durable.
 * Duplicate id+revision projections do not publish another artifact event. Each published event
 * carries the durable Task revision for exact-once subscribe reconciliation.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class HubPublishingA2aPersistedSnapshotHook implements A2aPersistedSnapshotHook {

  private static final Logger LOG = Logger.getLogger(HubPublishingA2aPersistedSnapshotHook.class);

  private final TaskEventHub eventHub;

  @Inject
  public HubPublishingA2aPersistedSnapshotHook(TaskEventHub eventHub) {
    this.eventHub = eventHub;
  }

  @Override
  public void onPersisted(
      String taskId,
      String contextId,
      A2aTaskState state,
      String publicSnapshotJson,
      List<CreateChainPublicArtifact> newlyCommittedArtifacts,
      long durableRevision) {
    Task task;
    try {
      task = JsonUtil.fromJson(publicSnapshotJson, Task.class);
    } catch (Exception e) {
      LOG.errorf(e, "Failed to deserialize persisted snapshot for taskId=%s", taskId);
      throw new IllegalStateException("Unable to deserialize Task snapshot for stream publish", e);
    }

    if (newlyCommittedArtifacts != null) {
      for (CreateChainPublicArtifact artifact : newlyCommittedArtifacts) {
        TaskArtifactUpdateEvent artifactUpdate =
            new TaskArtifactUpdateEvent(
                taskId,
                CreateChainA2aArtifactMapper.toSdkArtifact(artifact),
                contextId,
                false,
                true,
                null);
        LOG.debugf(
            "Persisted artifact ready for publish taskId=%s artifactId=%s revision=%d"
                + " durableRevision=%d",
            taskId, artifact.id(), artifact.revision(), durableRevision);
        eventHub.publish(taskId, artifactUpdate, durableRevision);
      }
    }

    TaskStatusUpdateEvent statusUpdate =
        new TaskStatusUpdateEvent(
            taskId,
            task.status() != null
                ? task.status()
                : new org.a2aproject.sdk.spec.TaskStatus(state.toSdk(), null, null),
            contextId,
            null);

    LOG.debugf(
        "Persisted snapshot ready for publish taskId=%s state=%s durableRevision=%d",
        taskId, state, durableRevision);
    eventHub.publish(taskId, statusUpdate, durableRevision);
  }
}
