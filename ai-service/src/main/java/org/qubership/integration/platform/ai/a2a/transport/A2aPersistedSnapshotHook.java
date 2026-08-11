package org.qubership.integration.platform.ai.a2a.transport;

import java.util.List;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainPublicArtifact;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Invoked after an A2A Task snapshot is persisted and before the transport response completes.
 *
 * <p>Prompt 05 wires streaming subscribers to this hook. Prompt 06 publishes artifact update frames
 * for newly committed public artifacts. Prompt 10 attaches the durable Task revision so subscribe
 * reconciliation can suppress buffered duplicates.
 */
public interface A2aPersistedSnapshotHook {

  default void onPersisted(
      String taskId, String contextId, A2aTaskState state, String publicSnapshotJson) {
    onPersisted(taskId, contextId, state, publicSnapshotJson, List.of(), 1L);
  }

  default void onPersisted(
      String taskId,
      String contextId,
      A2aTaskState state,
      String publicSnapshotJson,
      List<CreateChainPublicArtifact> newlyCommittedArtifacts) {
    onPersisted(taskId, contextId, state, publicSnapshotJson, newlyCommittedArtifacts, 1L);
  }

  void onPersisted(
      String taskId,
      String contextId,
      A2aTaskState state,
      String publicSnapshotJson,
      List<CreateChainPublicArtifact> newlyCommittedArtifacts,
      long durableRevision);
}
