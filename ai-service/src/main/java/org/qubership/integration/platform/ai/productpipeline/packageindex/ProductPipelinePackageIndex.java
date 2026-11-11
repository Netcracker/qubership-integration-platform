package org.qubership.integration.platform.ai.productpipeline.packageindex;

import java.util.List;

/** Built product-pipeline package index exposed beside other qipknowledge indexes. */
public record ProductPipelinePackageIndex(
    String baselineId,
    String baselineDigest,
    String closureDigest,
    String knowledgeVersion,
    String languageVersion,
    List<CapabilityManifest> capabilities,
    List<DependencyPin> dependencyPins,
    List<ReferenceArtifact> referenceArtifacts) {

  public ProductPipelinePackageIndex {
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    dependencyPins = dependencyPins == null ? List.of() : List.copyOf(dependencyPins);
    referenceArtifacts = referenceArtifacts == null ? List.of() : List.copyOf(referenceArtifacts);
  }
}
