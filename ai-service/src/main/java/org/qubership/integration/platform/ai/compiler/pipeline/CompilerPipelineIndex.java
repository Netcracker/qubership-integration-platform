package org.qubership.integration.platform.ai.compiler.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

/** Compiled pipeline metadata for BUILD_CHAIN orchestration. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompilerPipelineIndex(
    int schemaVersion,
    QipKnowledgePackVersion packVersion,
    CompilerPipelineIndexSource sources,
    List<CompilerPipelineEntry> entries,
    CompilerPackageIdentity packageIdentity,
    Map<String, String> sourceDigests,
    List<CompilerPipelineNode> nodes,
    List<CompilerPipelineDependency> dependencies) {

  public CompilerPipelineIndex {
    entries = entries == null ? List.of() : List.copyOf(entries);
    sourceDigests = sourceDigests == null ? Map.of() : Map.copyOf(sourceDigests);
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
  }

  /** Schema-v1 compatibility constructor for historical pinned indexes. */
  public CompilerPipelineIndex(
      int schemaVersion,
      QipKnowledgePackVersion packVersion,
      CompilerPipelineIndexSource sources,
      List<CompilerPipelineEntry> entries) {
    this(schemaVersion, packVersion, sources, entries, null, Map.of(), List.of(), List.of());
  }
}
