package org.qubership.integration.platform.ai.a2a.transport;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainPublicArtifact;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/** Default no-op persist hook used when streaming is not wired. */
@ApplicationScoped
public class NoOpA2aPersistedSnapshotHook implements A2aPersistedSnapshotHook {

  @Override
  public void onPersisted(
      String taskId,
      String contextId,
      A2aTaskState state,
      String publicSnapshotJson,
      List<CreateChainPublicArtifact> newlyCommittedArtifacts,
      long durableRevision) {
    // Prompt 05 / 06 publish status and artifact SSE frames from HubPublishingA2aPersistedSnapshotHook.
  }
}
