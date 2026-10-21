package org.qubership.integration.platform.ai.productpipeline.packageindex;

import java.util.List;
import java.util.Map;

/** Frozen experimental-migration baseline used for controlled source-refresh audits. */
public record ReferenceBaseline(
    int schemaVersion,
    String baselineId,
    String baselineVersion,
    String root,
    Map<String, Object> referenceEvidence,
    Map<String, Map<String, String>> mapping,
    List<ReferenceArtifact> artifacts) {

  public ReferenceBaseline {
    referenceEvidence =
        referenceEvidence == null ? Map.of() : Map.copyOf(referenceEvidence);
    mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
  }
}
