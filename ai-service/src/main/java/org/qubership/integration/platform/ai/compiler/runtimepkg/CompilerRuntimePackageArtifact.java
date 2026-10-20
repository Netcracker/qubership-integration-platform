package org.qubership.integration.platform.ai.compiler.runtimepkg;

import java.util.List;

/** Thin index entry for one compiler-runtime-package artifact. */
public record CompilerRuntimePackageArtifact(
    String path,
    String artifactType,
    String checksum,
    List<String> topLevelKeys) {

  public CompilerRuntimePackageArtifact {
    topLevelKeys = topLevelKeys == null ? List.of() : List.copyOf(topLevelKeys);
  }
}
