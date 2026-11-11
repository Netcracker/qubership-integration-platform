package org.qubership.integration.platform.ai.productpipeline.profile;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Identifies an artifact type and its schema version. */
public record ArtifactTypeRef(String type, int schemaVersion) {

  public boolean matches(CompilationArtifacts.Kind kind) {
    if (kind == null || type == null || type.isBlank()) {
      return false;
    }
    String normalized = type.replace('-', '_');
    return kind.name().equalsIgnoreCase(normalized);
  }
}
