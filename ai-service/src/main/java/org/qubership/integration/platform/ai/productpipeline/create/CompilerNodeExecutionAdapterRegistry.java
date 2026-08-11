package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;

/** Registry for JAVA_ADAPTER planning executors keyed by adapter ID. */
@ApplicationScoped
public class CompilerNodeExecutionAdapterRegistry {

  private final Map<String, CompilerNodeExecutionAdapter> byAdapterId;

  @Inject
  public CompilerNodeExecutionAdapterRegistry(
      @Any Instance<CompilerNodeExecutionAdapter> adapters,
      QipKnowledgePackRepository packRepository) {
    this(index(adapters));
    validatePinnedAdapterCoverage(packRepository);
  }

  /** Test-only constructor with explicit adapter map. */
  public static CompilerNodeExecutionAdapterRegistry forTest(
      Map<String, CompilerNodeExecutionAdapter> adapters) {
    return new CompilerNodeExecutionAdapterRegistry(Map.copyOf(adapters));
  }

  private CompilerNodeExecutionAdapterRegistry(Map<String, CompilerNodeExecutionAdapter> byAdapterId) {
    this.byAdapterId = Objects.requireNonNull(byAdapterId, "byAdapterId");
  }

  public Optional<CompilerNodeExecutionAdapter> find(String adapterId) {
    if (adapterId == null || adapterId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byAdapterId.get(adapterId));
  }

  public CompilerNodeExecutionAdapter require(String adapterId) {
    return find(adapterId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No CompilerNodeExecutionAdapter registered for: " + adapterId));
  }

  private static Map<String, CompilerNodeExecutionAdapter> index(
      Instance<CompilerNodeExecutionAdapter> adapters) {
    Map<String, CompilerNodeExecutionAdapter> map = new HashMap<>();
    for (CompilerNodeExecutionAdapter adapter : adapters) {
      String id = adapter.adapterId();
      if (id == null || id.isBlank()) {
        throw new IllegalStateException(
            "CompilerNodeExecutionAdapter has blank adapterId: " + adapter.getClass().getName());
      }
      if (map.put(id, adapter) != null) {
        throw new IllegalStateException(
            "Duplicate CompilerNodeExecutionAdapter registration for: " + id);
      }
    }
    return map;
  }

  private void validatePinnedAdapterCoverage(QipKnowledgePackRepository packRepository) {
    if (packRepository == null) {
      return;
    }
    var index = packRepository.loadCompilerPipelineIndex();
    for (var node : index.nodes()) {
      if (node.executionMode() != CompilerNodeExecutionMode.JAVA_ADAPTER) {
        continue;
      }
      String adapterId = node.adapterId();
      if (adapterId == null || adapterId.isBlank()) {
        throw new IllegalStateException(
            "Pinned JAVA_ADAPTER node has blank adapterId: " + node.skillId());
      }
      if (!byAdapterId.containsKey(adapterId)) {
        throw new IllegalStateException(
            "Missing CompilerNodeExecutionAdapter for pinned adapterId: " + adapterId);
      }
    }
  }
}
