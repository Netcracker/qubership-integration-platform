package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Immutable full-chain semantic revision. Unsupported schema versions fail closed.
 */
public record ChainSemanticRevision(
    String schemaVersion,
    String revisionId,
    String chainIdentity,
    String compilerContractVersion,
    List<SemanticEntryPoint> entryPoints,
    List<SemanticNode> nodes,
    List<SemanticRegion> regions,
    List<SemanticExecutionEdge> executionEdges,
    List<SemanticContainment> containment,
    List<MappingIntent> mappingIntents,
    List<String> constraints,
    List<String> assumptions,
    List<QipKnowledgeCitation> citations) {

  public static final String CURRENT_SCHEMA_VERSION = "chain-semantic-revision/v1";

  public ChainSemanticRevision {
    if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported semantic schema version: " + schemaVersion);
    }
    revisionId = DesignArtifacts.requireText(revisionId, "revisionId");
    chainIdentity = DesignArtifacts.requireText(chainIdentity, "chainIdentity");
    compilerContractVersion =
        DesignArtifacts.requireText(compilerContractVersion, "compilerContractVersion");
    entryPoints = DesignArtifacts.copyList(entryPoints);
    nodes = DesignArtifacts.copyList(nodes);
    regions = DesignArtifacts.copyList(regions);
    executionEdges = DesignArtifacts.copyList(executionEdges);
    containment = DesignArtifacts.copyList(containment);
    mappingIntents = DesignArtifacts.copyList(mappingIntents);
    constraints = DesignArtifacts.copyList(constraints);
    assumptions = DesignArtifacts.copyList(assumptions);
    citations = DesignArtifacts.copyList(citations);
  }

  /**
   * Mapping rule bodies. The requirement brief is authoritative when present. A stored
   * {@link #mappingIntents()} list is the legacy snapshot for older payloads.
   */
  public List<MappingIntent> mappingBodies(RequirementBrief brief) {
    if (brief != null) {
      return brief.mappingIntents();
    }
    return mappingIntents();
  }
}
