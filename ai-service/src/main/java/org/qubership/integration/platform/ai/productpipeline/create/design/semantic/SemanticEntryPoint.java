package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/**
 * Trigger occurrence that starts one independent exchange. Presentation order is explicit and
 * does not come from list position.
 */
public record SemanticEntryPoint(
    String entryPointId,
    String triggerNodeId,
    String initialTargetNodeId,
    int order,
    SemanticProvenance provenance,
    Presentation presentation) {

  public SemanticEntryPoint {
    entryPointId = DesignArtifacts.requireText(entryPointId, "entryPointId");
    triggerNodeId = DesignArtifacts.requireText(triggerNodeId, "triggerNodeId");
    initialTargetNodeId = DesignArtifacts.requireText(initialTargetNodeId, "initialTargetNodeId");
    provenance = provenance == null ? new SemanticProvenance(List.of()) : provenance;
    presentation = presentation == null ? new Presentation(null, null) : presentation;
  }

  public SemanticEntryPoint(String entryPointId, String triggerNodeId) {
    this(
        entryPointId,
        triggerNodeId,
        "op-shared",
        0,
        new SemanticProvenance(List.of()),
        new Presentation(null, null));
  }

  /** Human-readable labels that do not change execution. */
  public record Presentation(String label, String description) {}
}
