package org.qubership.integration.platform.ai.a2a.artifacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;

/** Converts public create-chain artifacts into A2A SDK {@link Artifact} values. */
public final class CreateChainA2aArtifactMapper {

  private CreateChainA2aArtifactMapper() {}

  public static Artifact toSdkArtifact(CreateChainPublicArtifact artifact) {
    Objects.requireNonNull(artifact, "artifact");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("type", artifact.type());
    metadata.put("revision", artifact.revision());
    metadata.put("contentHash", artifact.contentHash());
    return Artifact.builder()
        .artifactId(artifact.id())
        .name(artifact.type())
        .parts(List.of(new DataPart(new LinkedHashMap<>(artifact.payload()))))
        .metadata(metadata)
        .build();
  }

  public static List<Artifact> toSdkArtifacts(List<CreateChainPublicArtifact> artifacts) {
    if (artifacts == null || artifacts.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<Artifact> mapped = new ArrayList<>();
    for (CreateChainPublicArtifact artifact : artifacts) {
      if (seen.add(artifact.revisionKey())) {
        mapped.add(toSdkArtifact(artifact));
      }
    }
    return List.copyOf(mapped);
  }

  public static List<CreateChainPublicArtifact> mergeIdempotent(
      List<CreateChainPublicArtifact> existing, List<CreateChainPublicArtifact> incoming) {
    LinkedHashMap<String, CreateChainPublicArtifact> merged = new LinkedHashMap<>();
    if (existing != null) {
      for (CreateChainPublicArtifact artifact : existing) {
        merged.put(artifact.revisionKey(), artifact);
      }
    }
    if (incoming != null) {
      for (CreateChainPublicArtifact artifact : incoming) {
        merged.putIfAbsent(artifact.revisionKey(), artifact);
      }
    }
    return List.copyOf(merged.values());
  }

  /** Returns artifacts in {@code incoming} that are absent from {@code existing} by id+revision. */
  public static List<CreateChainPublicArtifact> newlyCommitted(
      List<CreateChainPublicArtifact> existing, List<CreateChainPublicArtifact> incoming) {
    LinkedHashSet<String> known = new LinkedHashSet<>();
    if (existing != null) {
      for (CreateChainPublicArtifact artifact : existing) {
        known.add(artifact.revisionKey());
      }
    }
    List<CreateChainPublicArtifact> fresh = new ArrayList<>();
    if (incoming != null) {
      for (CreateChainPublicArtifact artifact : incoming) {
        if (known.add(artifact.revisionKey())) {
          fresh.add(artifact);
        }
      }
    }
    return List.copyOf(fresh);
  }

  public static Optional<CreateChainPublicArtifact> findByRevisionKey(
      List<CreateChainPublicArtifact> artifacts, String revisionKey) {
    if (artifacts == null || revisionKey == null) {
      return Optional.empty();
    }
    return artifacts.stream().filter(a -> a.revisionKey().equals(revisionKey)).findFirst();
  }
}
