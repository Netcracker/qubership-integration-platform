package org.qubership.integration.platform.ai.productpipeline.create.design.model;

/**
 * Durable IDS document stored for PROVIDE, GENERATE, and DERIVE design-input modes.
 *
 * @param schemaVersion artifact payload schema version
 * @param mode how the document was produced
 * @param sourceReference approved brief or upload reference that produced the document
 * @param sourceHash content hash of the source artifact
 * @param normalizedFlowHash content hash of the extracted {@link NormalizedDesignFlow}
 * @param rendererVersion renderer identity for DERIVE; unused markers are allowed for other modes
 * @param markdown exact IDS markdown content
 */
public record IdsDocument(
    String schemaVersion,
    Mode mode,
    String sourceReference,
    String sourceHash,
    String normalizedFlowHash,
    String rendererVersion,
    String markdown) {

  public enum Mode {
    PROVIDED,
    GENERATED,
    DERIVED
  }

  public IdsDocument {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    mode = DesignArtifacts.requireNonNull(mode, "mode");
    sourceReference = DesignArtifacts.requireText(sourceReference, "sourceReference");
    sourceHash = DesignArtifacts.requireText(sourceHash, "sourceHash");
    normalizedFlowHash = DesignArtifacts.requireText(normalizedFlowHash, "normalizedFlowHash");
    rendererVersion = DesignArtifacts.requireText(rendererVersion, "rendererVersion");
    markdown = DesignArtifacts.requireText(markdown, "markdown");
  }
}
