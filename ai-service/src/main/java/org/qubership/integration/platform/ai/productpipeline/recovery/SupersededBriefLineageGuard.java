package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;

/** Rejects compile inputs derived from a brief revision that was superseded after repair approval. */
public final class SupersededBriefLineageGuard {

  private SupersededBriefLineageGuard() {}

  public static String supersededBriefHash(Map<String, Object> attributes) {
    if (attributes == null) {
      return null;
    }
    Object value = attributes.get(ProductPipelineRunSupport.SUPERSEDED_BRIEF_CONTENT_HASH_ATTR);
    if (value instanceof String text && !text.isBlank()) {
      return text.trim();
    }
    return null;
  }

  public static Set<String> supersededArtifactHashes(Map<String, Object> attributes) {
    if (attributes == null) {
      return Set.of();
    }
    Object value = attributes.get(ProductPipelineRunSupport.SUPERSEDED_ARTIFACT_HASHES_ATTR);
    if (!(value instanceof List<?> list)) {
      return Set.of();
    }
    Set<String> hashes = new LinkedHashSet<>();
    for (Object item : list) {
      if (item instanceof String text && !text.isBlank()) {
        hashes.add(text.trim());
      }
    }
    return Set.copyOf(hashes);
  }

  public static boolean isSupersededBriefHash(String supersededHash, String briefHash) {
    return supersededHash != null
        && !supersededHash.isBlank()
        && briefHash != null
        && supersededHash.equals(briefHash);
  }

  public static Optional<String> originatingBriefHash(
      ProductPipelineArtifactStore store, String runId, Revision revision) {
    Objects.requireNonNull(store, "store");
    if (runId == null || runId.isBlank() || revision == null) {
      return Optional.empty();
    }
    ArrayDeque<Revision> queue = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    queue.addLast(revision);
    while (!queue.isEmpty()) {
      Revision current = queue.removeFirst();
      String visitKey = current.kind() + ":" + current.artifactId();
      if (!visited.add(visitKey)) {
        continue;
      }
      for (Reference input : current.inputs()) {
        if (input == null || input.kind() == null) {
          continue;
        }
        if (input.kind() == Kind.REQUIREMENT_BRIEF) {
          return Optional.of(input.contentHash());
        }
        store.get(runId, input).ifPresent(queue::addLast);
      }
    }
    return Optional.empty();
  }

  public static boolean isSupersededCompileInput(
      ProductPipelineArtifactStore store,
      String runId,
      Map<String, Object> attributes,
      Revision revision) {
    if (revision == null) {
      return false;
    }
    if (supersededArtifactHashes(attributes).contains(revision.contentHash())) {
      return true;
    }
    String supersededBriefHash = supersededBriefHash(attributes);
    if (supersededBriefHash == null || supersededBriefHash.isBlank()) {
      return false;
    }
    return originatingBriefHash(store, runId, revision)
        .map(hash -> supersededBriefHash.equals(hash))
        .orElse(false);
  }
}
