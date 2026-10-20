package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceSnapshot;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;

/** Feature-gated read-only view of a durable product-pipeline CREATE run. */
public record ProductPipelineRunView(
    String conversationId,
    String currentState,
    long runRevision,
    RunManifest runManifest,
    List<StageAttempt> attempts,
    List<RunTransition> transitions,
    List<String> committedArtifactKinds,
    Map<String, Object> decodedArtifacts,
    EvidenceSnapshot.Knowledge knowledgeContext,
    String compilerPackageDigest,
    String pipelineIndexDigest,
    String resolvedDagDigest,
    String approvedPlanContentHash,
    String materializedChainId,
    Boolean reconcileMatches) {

  public ProductPipelineRunView {
    attempts = attempts == null ? List.of() : List.copyOf(attempts);
    transitions = transitions == null ? List.of() : List.copyOf(transitions);
    committedArtifactKinds =
        committedArtifactKinds == null ? List.of() : List.copyOf(committedArtifactKinds);
    decodedArtifacts = decodedArtifacts == null ? Map.of() : Map.copyOf(decodedArtifacts);
  }
}
