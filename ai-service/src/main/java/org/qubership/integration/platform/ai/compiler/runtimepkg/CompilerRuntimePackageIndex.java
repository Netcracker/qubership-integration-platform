package org.qubership.integration.platform.ai.compiler.runtimepkg;

import java.util.List;
import java.util.Optional;

/** Thin index of compiler-runtime-package artifacts available to backend context builders. */
public record CompilerRuntimePackageIndex(List<CompilerRuntimePackageArtifact> artifacts) {

  public CompilerRuntimePackageIndex {
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
  }

  public Optional<CompilerRuntimePackageArtifact> findByType(String artifactType) {
    return artifacts.stream().filter(artifact -> artifact.artifactType().equals(artifactType)).findFirst();
  }
}
