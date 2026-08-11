package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;

/** Immutable resolved compiler DAG pinned for one product-pipeline run. */
public record ResolvedCompilerDag(
    List<ResolvedCompilerNode> nodes,
    List<CompilerPipelineDependency> dependencies,
    String digest) {

  public ResolvedCompilerDag {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
  }
}
